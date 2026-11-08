// Группы v2: участники с личными пропусками, администраторы, одобрение новичков,
// приглашения со сроком, удаление участника и смена ключа группы.
// Сервер по-прежнему не знает ключа группы: хранит зашифрованные сообщения и
// «конверты» с ключом, зашифрованные лично для каждого участника.
package main

import (
	"bufio"
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"time"
)

var idRe = regexp.MustCompile(`^[0-9a-z]{6,32}$`)

type member2 struct {
	ID      string `json:"id"`
	TokHash string `json:"th"`
	Role    string `json:"role"` // owner, admin, member, pending, removed, left
	Profile string `json:"profile"`
	Joined  int64  `json:"joined"`
	Seen    int64  `json:"seen"`
	Changed int64  `json:"changed"`
}

type invite2 struct {
	Code    string `json:"code"`
	By      string `json:"by"`
	Expires int64  `json:"expires"`
	Uses    int    `json:"uses"` // 0 — без ограничения
	Used    int    `json:"used"`
	Created int64  `json:"created"`
}

type meta2 struct {
	Members map[string]*member2          `json:"members"`
	Invites map[string]*invite2          `json:"invites"`
	Epoch   int                          `json:"epoch"`
	Boxes   map[string]map[string]string `json:"boxes"` // эпоха → участник → конверт
	MVer    int64                        `json:"mver"`
}

type msg2 struct {
	S int64  `json:"s"`
	T int64  `json:"t"`
	M string `json:"m"`
	B string `json:"b"`
}

type loc2 struct {
	M string `json:"m"`
	S int64  `json:"s"`
	T int64  `json:"t"`
	B string `json:"b"`
}

type room2 struct {
	mu   sync.Mutex
	id   string
	dir  string
	meta meta2
	msgs []msg2
	last int64
	locs map[string]loc2
	wake chan struct{}
	file *os.File
}

type hub2 struct {
	mu    sync.Mutex
	rooms map[string]*room2
	dir   string
}

func now() int64 { return time.Now().UnixMilli() }

func hashTok(t string) string {
	h := sha256.Sum256([]byte("rubezh-tok|" + t))
	return hex.EncodeToString(h[:])
}

func randHex(n int) string {
	b := make([]byte, n)
	rand.Read(b)
	return hex.EncodeToString(b)
}

func (h *hub2) open(id string, create bool) (*room2, error) {
	h.mu.Lock()
	defer h.mu.Unlock()
	if r, ok := h.rooms[id]; ok {
		return r, nil
	}
	dir := filepath.Join(h.dir, "v2", id)
	mp := filepath.Join(dir, "meta.json")
	r := &room2{id: id, dir: dir, locs: map[string]loc2{}, wake: make(chan struct{})}
	if b, err := os.ReadFile(mp); err == nil {
		if err := json.Unmarshal(b, &r.meta); err != nil {
			return nil, err
		}
	} else if !create {
		return nil, os.ErrNotExist
	} else {
		if err := os.MkdirAll(dir, 0700); err != nil {
			return nil, err
		}
		r.meta = meta2{Members: map[string]*member2{}, Invites: map[string]*invite2{}, Boxes: map[string]map[string]string{}, Epoch: 1}
	}
	lp := filepath.Join(dir, "msgs.log")
	if f, err := os.Open(lp); err == nil {
		sc := bufio.NewScanner(f)
		sc.Buffer(make([]byte, 1<<20), maxBlob*2)
		for sc.Scan() {
			p := strings.SplitN(sc.Text(), "\t", 4)
			if len(p) != 4 {
				continue
			}
			s, _ := strconv.ParseInt(p[0], 10, 64)
			t, _ := strconv.ParseInt(p[1], 10, 64)
			r.msgs = append(r.msgs, msg2{s, t, p[2], p[3]})
			r.last = s
		}
		f.Close()
		if len(r.msgs) > keepMsgs {
			r.msgs = r.msgs[len(r.msgs)-keepMsgs:]
		}
	}
	f, err := os.OpenFile(lp, os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0600)
	if err != nil {
		return nil, err
	}
	r.file = f
	h.rooms[id] = r
	return r, nil
}

// save — под r.mu.
func (r *room2) save() {
	b, _ := json.Marshal(r.meta)
	tmp := filepath.Join(r.dir, "meta.json.tmp")
	if os.WriteFile(tmp, b, 0600) == nil {
		os.Rename(tmp, filepath.Join(r.dir, "meta.json"))
	}
}

// bump — изменился состав: разбудить ожидающих.
func (r *room2) bump() {
	r.meta.MVer++
	r.last++
	close(r.wake)
	r.wake = make(chan struct{})
}

func jsonOut(w http.ResponseWriter, v interface{}) {
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	json.NewEncoder(w).Encode(v)
}

func fail(w http.ResponseWriter, code int, msg string) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(code)
	json.NewEncoder(w).Encode(map[string]string{"error": msg})
}

func readJSON(req *http.Request, v interface{}) error {
	b, err := io.ReadAll(io.LimitReader(req.Body, maxBlob+1))
	if err != nil || len(b) > maxBlob {
		return fmt.Errorf("big")
	}
	return json.Unmarshal(b, v)
}

func isAdmin(m *member2) bool { return m.Role == "owner" || m.Role == "admin" }
func isActive(m *member2) bool { return isAdmin(m) || m.Role == "member" }

func registerV2(dir string) {
	h := &hub2{rooms: map[string]*room2{}, dir: dir}

	// auth: заголовок Authorization: Bearer <member>:<token>
	auth := func(w http.ResponseWriter, req *http.Request, need string) (*room2, *member2, bool) {
		id := req.URL.Query().Get("room")
		if !roomRe.MatchString(id) {
			fail(w, 400, "bad room")
			return nil, nil, false
		}
		a := strings.TrimPrefix(req.Header.Get("Authorization"), "Bearer ")
		p := strings.SplitN(a, ":", 2)
		if len(p) != 2 {
			fail(w, 401, "no auth")
			return nil, nil, false
		}
		r, err := h.open(id, false)
		if err != nil {
			fail(w, 404, "no room")
			return nil, nil, false
		}
		r.mu.Lock()
		m := r.meta.Members[p[0]]
		ok := m != nil && m.TokHash == hashTok(p[1])
		if ok {
			switch need {
			case "admin":
				ok = isAdmin(m)
			case "member":
				ok = isActive(m)
			case "any":
				ok = m.Role != "removed" && m.Role != "left"
			}
		}
		if !ok {
			r.mu.Unlock()
			if m != nil && (m.Role == "removed" || m.Role == "left") {
				fail(w, 403, "removed")
			} else {
				fail(w, 403, "forbidden")
			}
			return nil, nil, false
		}
		m.Seen = now()
		return r, m, true // r.mu остаётся захваченным — вызывающий обязан Unlock
	}

	type joinReq struct {
		Code    string `json:"code"`
		Member  string `json:"member"`
		Token   string `json:"token"`
		Profile string `json:"profile"`
	}

	http.HandleFunc("/api/v2/create", func(w http.ResponseWriter, req *http.Request) {
		id := req.URL.Query().Get("room")
		var j joinReq
		if req.Method != http.MethodPost || !roomRe.MatchString(id) || readJSON(req, &j) != nil || !idRe.MatchString(j.Member) || len(j.Token) < 16 || len(j.Profile) > 4096 {
			fail(w, 400, "bad request")
			return
		}
		if _, err := os.Stat(filepath.Join(dir, "v2", id, "meta.json")); err == nil {
			fail(w, 409, "exists")
			return
		}
		r, err := h.open(id, true)
		if err != nil {
			fail(w, 500, "storage")
			return
		}
		r.mu.Lock()
		defer r.mu.Unlock()
		if len(r.meta.Members) > 0 {
			fail(w, 409, "exists")
			return
		}
		t := now()
		r.meta.Members[j.Member] = &member2{j.Member, hashTok(j.Token), "owner", j.Profile, t, t, t}
		r.bump()
		r.save()
		jsonOut(w, map[string]interface{}{"ok": true, "epoch": r.meta.Epoch})
	})

	http.HandleFunc("/api/v2/join", func(w http.ResponseWriter, req *http.Request) {
		id := req.URL.Query().Get("room")
		var j joinReq
		if req.Method != http.MethodPost || !roomRe.MatchString(id) || readJSON(req, &j) != nil || !idRe.MatchString(j.Member) || len(j.Token) < 16 || len(j.Profile) > 4096 {
			fail(w, 400, "bad request")
			return
		}
		r, err := h.open(id, false)
		if err != nil {
			fail(w, 404, "no room")
			return
		}
		r.mu.Lock()
		defer r.mu.Unlock()
		inv := r.meta.Invites[j.Code]
		t := now()
		if inv == nil || inv.Expires < t || (inv.Uses > 0 && inv.Used >= inv.Uses) {
			fail(w, 403, "invite expired")
			return
		}
		if old := r.meta.Members[j.Member]; old != nil {
			fail(w, 409, "member exists")
			return
		}
		inv.Used++
		r.meta.Members[j.Member] = &member2{j.Member, hashTok(j.Token), "pending", j.Profile, t, t, t}
		r.bump()
		r.save()
		jsonOut(w, map[string]interface{}{"ok": true, "role": "pending"})
	})

	http.HandleFunc("/api/v2/me", func(w http.ResponseWriter, req *http.Request) {
		r, m, ok := auth(w, req, "any")
		if !ok {
			return
		}
		defer r.mu.Unlock()
		jsonOut(w, map[string]interface{}{"role": m.Role, "epoch": r.meta.Epoch, "mver": r.meta.MVer})
	})

	http.HandleFunc("/api/v2/profile", func(w http.ResponseWriter, req *http.Request) {
		r, m, ok := auth(w, req, "any")
		if !ok {
			return
		}
		defer r.mu.Unlock()
		var j joinReq
		if readJSON(req, &j) != nil || len(j.Profile) > 4096 {
			fail(w, 400, "bad request")
			return
		}
		m.Profile = j.Profile
		m.Changed = now()
		r.bump()
		r.save()
		jsonOut(w, map[string]bool{"ok": true})
	})

	http.HandleFunc("/api/v2/members", func(w http.ResponseWriter, req *http.Request) {
		r, _, ok := auth(w, req, "member")
		if !ok {
			return
		}
		defer r.mu.Unlock()
		list := []*member2{}
		for _, m := range r.meta.Members {
			c := *m
			c.TokHash = ""
			list = append(list, &c)
		}
		jsonOut(w, map[string]interface{}{"members": list, "epoch": r.meta.Epoch, "mver": r.meta.MVer})
	})

	http.HandleFunc("/api/v2/invite", func(w http.ResponseWriter, req *http.Request) {
		r, m, ok := auth(w, req, "admin")
		if !ok {
			return
		}
		defer r.mu.Unlock()
		var j struct {
			Hours int `json:"hours"`
			Uses  int `json:"uses"`
		}
		if readJSON(req, &j) != nil || j.Hours < 1 || j.Hours > 24*30 || j.Uses < 0 || j.Uses > 100 {
			fail(w, 400, "bad request")
			return
		}
		t := now()
		for c, inv := range r.meta.Invites { // убрать просроченные
			if inv.Expires < t {
				delete(r.meta.Invites, c)
			}
		}
		inv := &invite2{randHex(12), m.ID, t + int64(j.Hours)*3600_000, j.Uses, 0, t}
		r.meta.Invites[inv.Code] = inv
		r.save()
		jsonOut(w, inv)
	})

	http.HandleFunc("/api/v2/invites", func(w http.ResponseWriter, req *http.Request) {
		r, _, ok := auth(w, req, "admin")
		if !ok {
			return
		}
		defer r.mu.Unlock()
		if req.Method == http.MethodPost { // отзыв
			var j struct {
				Code string `json:"code"`
			}
			if readJSON(req, &j) == nil {
				delete(r.meta.Invites, j.Code)
				r.save()
			}
		}
		list := []*invite2{}
		t := now()
		for _, inv := range r.meta.Invites {
			if inv.Expires > t && (inv.Uses == 0 || inv.Used < inv.Uses) {
				list = append(list, inv)
			}
		}
		jsonOut(w, map[string]interface{}{"invites": list})
	})

	// approve / role / remove: действия администратора над участником
	http.HandleFunc("/api/v2/manage", func(w http.ResponseWriter, req *http.Request) {
		r, me, ok := auth(w, req, "admin")
		if !ok {
			return
		}
		defer r.mu.Unlock()
		var j struct {
			Action string `json:"action"`
			Member string `json:"member"`
			Epoch  int    `json:"epoch"`
			Box    string `json:"box"`
		}
		if readJSON(req, &j) != nil {
			fail(w, 400, "bad request")
			return
		}
		m := r.meta.Members[j.Member]
		if m == nil {
			fail(w, 404, "no member")
			return
		}
		if m.Role == "owner" && j.Action != "approve" {
			fail(w, 403, "owner")
			return
		}
		switch j.Action {
		case "approve":
			if m.Role != "pending" || j.Epoch != r.meta.Epoch || j.Box == "" {
				fail(w, 409, "state")
				return
			}
			m.Role = "member"
			e := strconv.Itoa(j.Epoch)
			if r.meta.Boxes[e] == nil {
				r.meta.Boxes[e] = map[string]string{}
			}
			r.meta.Boxes[e][m.ID] = j.Box
		case "reject", "remove":
			if m.ID == me.ID {
				fail(w, 403, "self")
				return
			}
			m.Role = "removed"
			delete(r.locs, m.ID)
		case "admin":
			if !isActive(m) {
				fail(w, 409, "state")
				return
			}
			m.Role = "admin"
		case "member":
			if me.Role != "owner" && m.Role == "admin" && m.ID != me.ID {
				fail(w, 403, "owner only")
				return
			}
			m.Role = "member"
		default:
			fail(w, 400, "action")
			return
		}
		m.Changed = now()
		r.bump()
		r.save()
		jsonOut(w, map[string]bool{"ok": true})
	})

	// rekey: новая эпоха, конверты для всех активных участников
	http.HandleFunc("/api/v2/rekey", func(w http.ResponseWriter, req *http.Request) {
		r, _, ok := auth(w, req, "admin")
		if !ok {
			return
		}
		defer r.mu.Unlock()
		var j struct {
			Epoch int               `json:"epoch"`
			Boxes map[string]string `json:"boxes"`
		}
		if readJSON(req, &j) != nil || j.Epoch != r.meta.Epoch+1 {
			fail(w, 409, "epoch")
			return
		}
		for id, m := range r.meta.Members {
			if isActive(m) && j.Boxes[id] == "" {
				fail(w, 409, "missing box "+id)
				return
			}
		}
		boxes := map[string]string{}
		for id, b := range j.Boxes {
			if m := r.meta.Members[id]; m != nil && isActive(m) {
				boxes[id] = b
			}
		}
		r.meta.Epoch = j.Epoch
		r.meta.Boxes[strconv.Itoa(j.Epoch)] = boxes
		r.bump()
		r.save()
		jsonOut(w, map[string]interface{}{"ok": true, "epoch": r.meta.Epoch})
	})

	http.HandleFunc("/api/v2/keys", func(w http.ResponseWriter, req *http.Request) {
		r, m, ok := auth(w, req, "member")
		if !ok {
			return
		}
		defer r.mu.Unlock()
		out := map[string]string{}
		for e, bs := range r.meta.Boxes {
			if b, ok := bs[m.ID]; ok {
				out[e] = b
			}
		}
		jsonOut(w, map[string]interface{}{"epoch": r.meta.Epoch, "boxes": out})
	})

	http.HandleFunc("/api/v2/leave", func(w http.ResponseWriter, req *http.Request) {
		r, m, ok := auth(w, req, "any")
		if !ok {
			return
		}
		defer r.mu.Unlock()
		if m.Role == "owner" {
			fail(w, 403, "owner")
			return
		}
		m.Role = "left"
		m.Changed = now()
		delete(r.locs, m.ID)
		r.bump()
		r.save()
		jsonOut(w, map[string]bool{"ok": true})
	})

	http.HandleFunc("/api/v2/send", func(w http.ResponseWriter, req *http.Request) {
		r, m, ok := auth(w, req, "member")
		if !ok {
			return
		}
		defer r.mu.Unlock()
		b, err := io.ReadAll(io.LimitReader(req.Body, maxBlob+1))
		s := strings.TrimSpace(string(b))
		if err != nil || len(s) == 0 || len(b) > maxBlob || strings.ContainsAny(s, "\t\n\r") {
			fail(w, 400, "bad body")
			return
		}
		r.last++
		x := msg2{r.last, now(), m.ID, s}
		r.msgs = append(r.msgs, x)
		if len(r.msgs) > keepMsgs {
			r.msgs = r.msgs[len(r.msgs)-keepMsgs:]
		}
		fmt.Fprintf(r.file, "%d\t%d\t%s\t%s\n", x.S, x.T, x.M, x.B)
		close(r.wake)
		r.wake = make(chan struct{})
		jsonOut(w, map[string]int64{"s": x.S, "t": x.T})
	})

	http.HandleFunc("/api/v2/loc", func(w http.ResponseWriter, req *http.Request) {
		r, m, ok := auth(w, req, "member")
		if !ok {
			return
		}
		defer r.mu.Unlock()
		b, err := io.ReadAll(io.LimitReader(req.Body, 4097))
		if err != nil || len(b) == 0 || len(b) > 4096 {
			fail(w, 400, "bad body")
			return
		}
		r.last++
		r.locs[m.ID] = loc2{m.ID, r.last, now(), strings.TrimSpace(string(b))}
		close(r.wake)
		r.wake = make(chan struct{})
		jsonOut(w, map[string]int64{"s": r.last})
	})

	http.HandleFunc("/api/v2/poll", func(w http.ResponseWriter, req *http.Request) {
		r, m, ok := auth(w, req, "any")
		if !ok {
			return
		}
		q := req.URL.Query()
		since, _ := strconv.ParseInt(q.Get("since"), 10, 64)
		lsince := since
		if v := q.Get("lsince"); v != "" {
			lsince, _ = strconv.ParseInt(v, 10, 64)
		}
		mver, _ := strconv.ParseInt(q.Get("mver"), 10, 64)
		wait, _ := strconv.Atoi(q.Get("wait"))
		if wait < 0 || wait > maxWait {
			wait = maxWait
		}
		r.mu.Unlock()
		deadline := time.After(time.Duration(wait) * time.Second)
		for {
			r.mu.Lock()
			role := m.Role
			msgs := []msg2{}
			locs := []loc2{}
			if isActive(m) {
				for _, x := range r.msgs {
					if x.S > since {
						msgs = append(msgs, x)
					}
				}
				if len(msgs) > 500 {
					msgs = msgs[:500]
				}
				old := time.Now().Add(-locTTL).UnixMilli()
				for id, l := range r.locs {
					if l.T < old {
						delete(r.locs, id)
						continue
					}
					if l.S > lsince {
						locs = append(locs, l)
					}
				}
			}
			last, wake, epoch, mv := r.last, r.wake, r.meta.Epoch, r.meta.MVer
			m.Seen = now()
			r.mu.Unlock()
			if len(msgs) > 0 || len(locs) > 0 || mv != mver || wait == 0 || role == "removed" || role == "left" {
				jsonOut(w, map[string]interface{}{"msgs": msgs, "locs": locs, "last": last, "epoch": epoch, "mver": mv, "role": role})
				return
			}
			select {
			case <-wake:
			case <-deadline:
				jsonOut(w, map[string]interface{}{"msgs": msgs, "locs": locs, "last": last, "epoch": epoch, "mver": mv, "role": role})
				return
			case <-req.Context().Done():
				return
			}
		}
	})
}
