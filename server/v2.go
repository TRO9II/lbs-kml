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
	X int64  `json:"x,omitempty"` // когда удалить (мс), 0 — хранить
	O string `json:"o,omitempty"` // личное сообщение: кому (видят только двое)
	B string `json:"b"`
}

const (
	maxBlob2   = 1536 << 10 // сообщение с фото или голосом
	maxPollOut = 4 << 20    // не больше ~4 МБ за один ответ
)

type loc2 struct {
	M string `json:"m"`
	S int64  `json:"s"`
	T int64  `json:"t"`
	B string `json:"b"`
}

type room2 struct {
	mu     sync.Mutex
	id     string
	dir    string
	meta   meta2
	msgs   []msg2
	last   int64
	locs   map[string]loc2
	reads  map[string]int64 // до какого номера участник прочитал
	rver   int64
	wake   chan struct{}
	file   *os.File
	logged int
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
	r := &room2{id: id, dir: dir, locs: map[string]loc2{}, reads: map[string]int64{}, wake: make(chan struct{})}
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
		sc.Buffer(make([]byte, 1<<20), maxBlob2*3)
		tnow := now()
		for sc.Scan() {
			p := strings.SplitN(sc.Text(), "\t", 6)
			var m msg2
			if len(p) == 6 {
				m.S, _ = strconv.ParseInt(p[0], 10, 64)
				m.T, _ = strconv.ParseInt(p[1], 10, 64)
				m.M = p[2]
				m.X, _ = strconv.ParseInt(p[3], 10, 64)
				m.O = p[4]
				m.B = p[5]
			} else if len(p) == 5 {
				m.S, _ = strconv.ParseInt(p[0], 10, 64)
				m.T, _ = strconv.ParseInt(p[1], 10, 64)
				m.M = p[2]
				m.X, _ = strconv.ParseInt(p[3], 10, 64)
				m.B = p[4]
			} else if len(p) == 4 {
				m.S, _ = strconv.ParseInt(p[0], 10, 64)
				m.T, _ = strconv.ParseInt(p[1], 10, 64)
				m.M, m.B = p[2], p[3]
			} else {
				continue
			}
			r.logged++
			if m.S > r.last {
				r.last = m.S
			}
			if m.X > 0 && m.X < tnow {
				continue
			}
			r.msgs = append(r.msgs, m)
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
	if r.logged > len(r.msgs) {
		r.compact() // в журнале были просроченные — стереть их с диска
	}
	h.rooms[id] = r
	return r, nil
}

// compact — переписать журнал без удалённых и просроченных сообщений (под r.mu).
func (r *room2) compact() {
	lp := filepath.Join(r.dir, "msgs.log")
	tmp := lp + ".tmp"
	f, err := os.OpenFile(tmp, os.O_CREATE|os.O_TRUNC|os.O_WRONLY, 0600)
	if err != nil {
		return
	}
	w := bufio.NewWriter(f)
	for _, m := range r.msgs {
		fmt.Fprintf(w, "%d\t%d\t%s\t%d\t%s\t%s\n", m.S, m.T, m.M, m.X, m.O, m.B)
	}
	w.Flush()
	f.Close()
	if r.file != nil {
		r.file.Close()
	}
	os.Rename(tmp, lp)
	r.file, _ = os.OpenFile(lp, os.O_APPEND|os.O_WRONLY, 0600)
	r.logged = len(r.msgs)
}

// expire — убрать просроченные (под r.mu); true, если что-то удалено.
func (r *room2) expire() bool {
	t := now()
	k := 0
	for _, m := range r.msgs {
		if m.X == 0 || m.X > t {
			r.msgs[k] = m
			k++
		}
	}
	if k == len(r.msgs) {
		return false
	}
	r.msgs = r.msgs[:k]
	return true
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

func isAdmin(m *member2) bool  { return m.Role == "owner" || m.Role == "admin" }
func isActive(m *member2) bool { return isAdmin(m) || m.Role == "member" }

func registerV2(dir string) {
	h := &hub2{rooms: map[string]*room2{}, dir: dir}
	// раз в 5 минут физически удаляем исчезнувшие сообщения
	go func() {
		for range time.Tick(5 * time.Minute) {
			h.mu.Lock()
			rooms := make([]*room2, 0, len(h.rooms))
			for _, r := range h.rooms {
				rooms = append(rooms, r)
			}
			h.mu.Unlock()
			for _, r := range rooms {
				r.mu.Lock()
				if r.expire() {
					r.compact()
				}
				r.mu.Unlock()
			}
		}
	}()

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

	// destroy: создатель удаляет группу целиком — переписка стирается, у всех группа пропадает
	http.HandleFunc("/api/v2/destroy", func(w http.ResponseWriter, req *http.Request) {
		r, m, ok := auth(w, req, "admin")
		if !ok {
			return
		}
		defer r.mu.Unlock()
		if m.Role != "owner" {
			fail(w, 403, "owner only")
			return
		}
		t := now()
		for _, x := range r.meta.Members {
			x.Role = "removed"
			x.Changed = t
		}
		r.meta.Invites = map[string]*invite2{}
		r.meta.Boxes = map[string]map[string]string{}
		r.msgs = nil
		r.locs = map[string]loc2{}
		r.compact()
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
		b, err := io.ReadAll(io.LimitReader(req.Body, maxBlob2+1))
		s := strings.TrimSpace(string(b))
		if err != nil || len(s) == 0 || len(b) > maxBlob2 || strings.ContainsAny(s, "\t\n\r") {
			fail(w, 400, "bad body")
			return
		}
		var exp int64
		if ttl, _ := strconv.ParseInt(req.URL.Query().Get("ttl"), 10, 64); ttl > 0 && ttl <= 30*24*3600 {
			exp = now() + ttl*1000
		}
		to := req.URL.Query().Get("to")
		if to != "" {
			if t := r.meta.Members[to]; t == nil || !isActive(t) || to == m.ID {
				fail(w, 400, "bad recipient")
				return
			}
		}
		r.last++
		x := msg2{r.last, now(), m.ID, exp, to, s}
		r.msgs = append(r.msgs, x)
		if len(r.msgs) > keepMsgs {
			r.msgs = r.msgs[len(r.msgs)-keepMsgs:]
		}
		fmt.Fprintf(r.file, "%d\t%d\t%s\t%d\t%s\t%s\n", x.S, x.T, x.M, x.X, x.O, x.B)
		r.logged++
		if r.expire() || r.logged > len(r.msgs)*2+100 {
			r.compact()
		}
		close(r.wake)
		r.wake = make(chan struct{})
		jsonOut(w, map[string]int64{"s": x.S, "t": x.T})
	})

	// unsend: стереть сообщение с сервера (автор или администратор)
	http.HandleFunc("/api/v2/unsend", func(w http.ResponseWriter, req *http.Request) {
		r, m, ok := auth(w, req, "member")
		if !ok {
			return
		}
		defer r.mu.Unlock()
		seq, _ := strconv.ParseInt(req.URL.Query().Get("seq"), 10, 64)
		for i, x := range r.msgs {
			if x.S != seq {
				continue
			}
			if x.M != m.ID && !isAdmin(m) {
				fail(w, 403, "forbidden")
				return
			}
			r.msgs = append(r.msgs[:i], r.msgs[i+1:]...)
			r.compact()
			break
		}
		jsonOut(w, map[string]bool{"ok": true})
	})

	http.HandleFunc("/api/v2/loc", func(w http.ResponseWriter, req *http.Request) {
		r, m, ok := auth(w, req, "member")
		if !ok {
			return
		}
		defer r.mu.Unlock()
		b, err := io.ReadAll(io.LimitReader(req.Body, 8193))
		if err != nil || len(b) == 0 || len(b) > 8192 {
			fail(w, 400, "bad body")
			return
		}
		r.last++
		r.locs[m.ID] = loc2{m.ID, r.last, now(), strings.TrimSpace(string(b))}
		close(r.wake)
		r.wake = make(chan struct{})
		jsonOut(w, map[string]int64{"s": r.last})
	})

	http.HandleFunc("/api/v2/read", func(w http.ResponseWriter, req *http.Request) {
		r, m, ok := auth(w, req, "member")
		if !ok {
			return
		}
		defer r.mu.Unlock()
		seq, _ := strconv.ParseInt(req.URL.Query().Get("seq"), 10, 64)
		if seq > r.reads[m.ID] && seq <= r.last {
			r.reads[m.ID] = seq
			r.rver++
			close(r.wake)
			r.wake = make(chan struct{})
		}
		jsonOut(w, map[string]bool{"ok": true})
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
		rver, _ := strconv.ParseInt(q.Get("rver"), 10, 64)
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
			var reads map[string]int64
			if isActive(m) {
				size, tn := 0, now()
				for _, x := range r.msgs {
					if x.S > since && (x.X == 0 || x.X > tn) && (x.O == "" || x.O == m.ID || x.M == m.ID) {
						if len(msgs) >= 500 || (size > maxPollOut && len(msgs) > 0) {
							break
						}
						msgs = append(msgs, x)
						size += len(x.B)
					}
				}
				if r.rver != rver {
					reads = map[string]int64{}
					for k, v := range r.reads {
						reads[k] = v
					}
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
			last, wake, epoch, mv, rv := r.last, r.wake, r.meta.Epoch, r.meta.MVer, r.rver
			m.Seen = now()
			r.mu.Unlock()
			out := map[string]interface{}{"msgs": msgs, "locs": locs, "last": last, "epoch": epoch, "mver": mv, "role": role, "rver": rv, "now": now()}
			if reads != nil {
				out["reads"] = reads
			}
			if len(msgs) > 0 || len(locs) > 0 || mv != mver || reads != nil || wait == 0 || role == "removed" || role == "left" {
				jsonOut(w, out)
				return
			}
			select {
			case <-wake:
			case <-deadline:
				jsonOut(w, out)
				return
			case <-req.Context().Done():
				return
			}
		}
	})
}
