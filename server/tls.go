// HTTPS без домена: при первом запуске сервер создаёт свой сертификат (на 20 лет),
// а телефон запоминает его отпечаток — подменить сервер посередине уже нельзя.
// HTTP и HTTPS работают на одном и том же порту: по первому байту видно, что пришло.
package main

import (
	"bufio"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/hex"
	"encoding/pem"
	"log"
	"math/big"
	"net"
	"os"
	"path/filepath"
	"time"
)

func loadCert(dir string) (tls.Certificate, string, error) {
	cp, kp := filepath.Join(dir, "tls.crt"), filepath.Join(dir, "tls.key")
	if _, err := os.Stat(cp); err != nil {
		key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
		if err != nil {
			return tls.Certificate{}, "", err
		}
		sn, _ := rand.Int(rand.Reader, new(big.Int).Lsh(big.NewInt(1), 62))
		tpl := &x509.Certificate{
			SerialNumber: sn,
			Subject:      pkix.Name{CommonName: "rubezh"},
			NotBefore:    time.Now().Add(-24 * time.Hour),
			NotAfter:     time.Now().AddDate(20, 0, 0),
			KeyUsage:     x509.KeyUsageDigitalSignature,
			ExtKeyUsage:  []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
		}
		der, err := x509.CreateCertificate(rand.Reader, tpl, tpl, &key.PublicKey, key)
		if err != nil {
			return tls.Certificate{}, "", err
		}
		kd, _ := x509.MarshalECPrivateKey(key)
		os.WriteFile(kp, pem.EncodeToMemory(&pem.Block{Type: "EC PRIVATE KEY", Bytes: kd}), 0600)
		os.WriteFile(cp, pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der}), 0600)
	}
	c, err := tls.LoadX509KeyPair(cp, kp)
	if err != nil {
		return c, "", err
	}
	sum := sha256.Sum256(c.Certificate[0])
	return c, hex.EncodeToString(sum[:]), nil
}

// peekConn — соединение, у которого уже прочитан первый байт.
type peekConn struct {
	net.Conn
	r *bufio.Reader
}

func (c *peekConn) Read(b []byte) (int, error) { return c.r.Read(b) }

// dualListener отдаёт и обычные, и TLS-соединения.
type dualListener struct {
	net.Listener
	cfg *tls.Config
	ch  chan net.Conn
	err chan error
}

func newDual(l net.Listener, cfg *tls.Config) *dualListener {
	d := &dualListener{Listener: l, cfg: cfg, ch: make(chan net.Conn, 64), err: make(chan error, 1)}
	go func() {
		for {
			c, err := l.Accept()
			if err != nil {
				d.err <- err
				return
			}
			go func(c net.Conn) {
				c.SetReadDeadline(time.Now().Add(15 * time.Second))
				br := bufio.NewReader(c)
				b, err := br.Peek(1)
				c.SetReadDeadline(time.Time{})
				if err != nil {
					c.Close()
					return
				}
				pc := &peekConn{c, br}
				if b[0] == 0x16 { // TLS ClientHello
					d.ch <- tls.Server(pc, d.cfg)
				} else {
					d.ch <- pc
				}
			}(c)
		}
	}()
	return d
}

func (d *dualListener) Accept() (net.Conn, error) {
	select {
	case c := <-d.ch:
		return c, nil
	case err := <-d.err:
		return nil, err
	}
}

var certFP string

func listen(addr, dir string) net.Listener {
	l, err := net.Listen("tcp", addr)
	if err != nil {
		log.Fatal(err)
	}
	cert, fp, err := loadCert(dir)
	if err != nil {
		log.Printf("Рубеж: сертификат не создан (%v) — только HTTP", err)
		return l
	}
	certFP = fp
	log.Printf("Рубеж: HTTPS включён, отпечаток %s", fp)
	return newDual(l, &tls.Config{Certificates: []tls.Certificate{cert}, MinVersion: tls.VersionTLS12})
}
