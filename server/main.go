// Сервер «Рубеж»: почтовый ящик для зашифрованных сообщений группы.
// Сервер не знает ни ключа, ни содержимого: хранит и раздаёт непрозрачные зашифрованные блоки.
// Комната = идентификатор, производный от ключа группы (знают только участники).
package main

import (
	"bufio"
	"encoding/json"
	"flag"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"time"
)

const (
	keepMsgs = 5000      // сколько последних сообщений хранить в комнате
	maxBlob  = 256 << 10 // максимальный размер одного блока
	maxWait  = 25        // секунд ожидания при опросе
)

var roomRe = regexp.MustCompile(`^[0-9a-f]{32}$`)

type msg struct {
	S int64  `json:"s"`
	T int64  `json:"t"`
	B string `json:"b"`
}

type room struct {
	mu     sync.Mutex
	msgs   []msg
	last   int64
	wake   chan struct{}
	file   *os.File
	logged int
}

type hub struct {
	mu    sync.Mutex
	rooms map[string]*room
	dir   string
}

func (h *hub) get(id string) (*room, error) {
	h.mu.Lock()
	defer h.mu.Unlock()
	if r, ok := h.rooms[id]; ok {
		return r, nil
	}
	r := &room{wake: make(chan struct{})}
	path := filepath.Join(h.dir, id+".log")
	if f, err := os.Open(path); err == nil {
		sc := bufio.NewScanner(f)
		sc.Buffer(make([]byte, 1<<20), maxBlob*2)
		for sc.Scan() {
			p := strings.SplitN(sc.Text(), "\t", 3)
			if len(p) != 3 {
				continue
			}
			s, _ := strconv.ParseInt(p[0], 10, 64)
			t, _ := strconv.ParseInt(p[1], 10, 64)
			r.msgs = append(r.msgs, msg{s, t, p[2]})
			r.last = s
			r.logged++
		}
		f.Close()
		if len(r.msgs) > keepMsgs {
			r.msgs = r.msgs[len(r.msgs)-keepMsgs:]
		}
	}
	f, err := os.OpenFile(path, os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0600)
	if err != nil {
		return nil, err
	}
	r.file = f
	h.rooms[id] = r
	return r, nil
}

// compact переписывает журнал, когда он вырос вдвое больше лимита.
func (h *hub) compact(id string, r *room) {
	if r.logged < keepMsgs*2 {
		return
	}
	path := filepath.Join(h.dir, id+".log")
	tmp := path + ".tmp"
	f, err := os.OpenFile(tmp, os.O_CREATE|os.O_TRUNC|os.O_WRONLY, 0600)
	if err != nil {
		return
	}
	w := bufio.NewWriter(f)
	for _, m := range r.msgs {
		fmt.Fprintf(w, "%d\t%d\t%s\n", m.S, m.T, m.B)
	}
	w.Flush()
	f.Close()
	r.file.Close()
	os.Rename(tmp, path)
	r.file, _ = os.OpenFile(path, os.O_APPEND|os.O_WRONLY, 0600)
	r.logged = len(r.msgs)
}

func cors(w http.ResponseWriter) {
	w.Header().Set("Access-Control-Allow-Origin", "*")
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
}

func main() {
	addr := flag.String("addr", ":8080", "адрес и порт")
	dir := flag.String("data", "/var/lib/rubezh", "папка для данных")
	flag.Parse()
	if err := os.MkdirAll(*dir, 0700); err != nil {
		log.Fatal(err)
	}
	h := &hub{rooms: map[string]*room{}, dir: *dir}

	http.HandleFunc("/api/ping", func(w http.ResponseWriter, r *http.Request) {
		cors(w)
		fmt.Fprintf(w, `{"ok":true,"server":"rubezh","time":%d}`, time.Now().UnixMilli())
	})

	http.HandleFunc("/api/send", func(w http.ResponseWriter, req *http.Request) {
		cors(w)
		id := req.URL.Query().Get("room")
		if req.Method != http.MethodPost || !roomRe.MatchString(id) {
			http.Error(w, `{"error":"bad request"}`, 400)
			return
		}
		body, err := io.ReadAll(io.LimitReader(req.Body, maxBlob+1))
		if err != nil || len(body) == 0 || len(body) > maxBlob {
			http.Error(w, `{"error":"bad body"}`, 400)
			return
		}
		b := strings.TrimSpace(string(body))
		if strings.ContainsAny(b, "\t\n\r") {
			http.Error(w, `{"error":"bad body"}`, 400)
			return
		}
		r, err := h.get(id)
		if err != nil {
			http.Error(w, `{"error":"storage"}`, 500)
			return
		}
		r.mu.Lock()
		r.last++
		m := msg{r.last, time.Now().UnixMilli(), b}
		r.msgs = append(r.msgs, m)
		if len(r.msgs) > keepMsgs {
			r.msgs = r.msgs[len(r.msgs)-keepMsgs:]
		}
		fmt.Fprintf(r.file, "%d\t%d\t%s\n", m.S, m.T, m.B)
		r.logged++
		h.compact(id, r)
		close(r.wake)
		r.wake = make(chan struct{})
		r.mu.Unlock()
		fmt.Fprintf(w, `{"s":%d,"t":%d}`, m.S, m.T)
	})

	http.HandleFunc("/api/poll", func(w http.ResponseWriter, req *http.Request) {
		cors(w)
		q := req.URL.Query()
		id := q.Get("room")
		if !roomRe.MatchString(id) {
			http.Error(w, `{"error":"bad request"}`, 400)
			return
		}
		since, _ := strconv.ParseInt(q.Get("since"), 10, 64)
		wait, _ := strconv.Atoi(q.Get("wait"))
		if wait < 0 || wait > maxWait {
			wait = maxWait
		}
		r, err := h.get(id)
		if err != nil {
			http.Error(w, `{"error":"storage"}`, 500)
			return
		}
		deadline := time.After(time.Duration(wait) * time.Second)
		for {
			r.mu.Lock()
			var out []msg
			for _, m := range r.msgs {
				if m.S > since {
					out = append(out, m)
				}
			}
			if len(out) > 500 {
				out = out[:500]
			}
			last, wake := r.last, r.wake
			r.mu.Unlock()
			if len(out) > 0 || wait == 0 {
				if out == nil {
					out = []msg{}
				}
				json.NewEncoder(w).Encode(map[string]interface{}{"msgs": out, "last": last})
				return
			}
			select {
			case <-wake:
			case <-deadline:
				json.NewEncoder(w).Encode(map[string]interface{}{"msgs": []msg{}, "last": last})
				return
			case <-req.Context().Done():
				return
			}
		}
	})

	srv := &http.Server{Addr: *addr, ReadHeaderTimeout: 10 * time.Second, IdleTimeout: 60 * time.Second}
	log.Printf("Рубеж: сервер слушает %s, данные в %s", *addr, *dir)
	log.Fatal(srv.ListenAndServe())
}
