package libcore

import (
	"bytes"
	"context"
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"libcore/device"
	"libcore/ech"
	"log"
	"net"
	"net/http"
	"net/url"
	"os"
	"strconv"
	"sync"
	"sync/atomic"
	"time"

	"github.com/sagernet/quic-go"
	"github.com/sagernet/quic-go/http3"
	"github.com/sagernet/sing/common/metadata"
	"github.com/sagernet/sing/protocol/socks"
	"github.com/sagernet/sing/protocol/socks/socks5"
)

var errFailConnectSocks5 = errors.New("fail connect socks5")

type HTTPClient interface {
	RestrictedTLS()
	ModernTLS()
	PinnedTLS12()
	PinnedSHA256(sumHex string)
	// SetTimeout sets the whole-request timeout in milliseconds
	// (S-B3). NewHttpClient defaults to 30s; 0 disables it.
	SetTimeout(timeoutMs int32)
	// 框架只传 port 一个参数（OwnBox 版多要 username/password，框架侧无此调用，
	// 故这里收敛为单参数，握手时用空用户名/密码，与原行为一致）。
	TrySocks5(port int32)
	// TrySocks5Auth is TrySocks5 with username/password (0.2.29 -26:
	// the mixed inbound now requires auth; the app's own libcore clients —
	// egress check, subscription fetch, rule-set download — must authenticate).
	TrySocks5Auth(port int32, username string, password string)
	// TryBoxOutbound 经 mainInstance 默认 outbound 拨号（纯 TUN / 禁用 mixed 时用）。
	TryBoxOutbound()
	TryH3Direct()
	KeepAlive()
	NewRequest() HTTPRequest
	Close()
}

type HTTPRequest interface {
	SetURL(link string) error
	SetMethod(method string)
	SetHeader(key string, value string)
	SetContent(content []byte)
	SetContentString(content string)
	SetUserAgent(userAgent string)
	AllowInsecure()
	Execute() (HTTPResponse, error)
}

type HTTPResponse interface {
	GetHeader(string) *StringBox
	GetContent() ([]byte, error)
	GetContentString() (*StringBox, error)
	WriteTo(path string) error
}

var (
	_ HTTPClient   = (*httpClient)(nil)
	_ HTTPRequest  = (*httpRequest)(nil)
	_ HTTPResponse = (*httpResponse)(nil)
)

type httpClient struct {
	tls           tls.Config
	h1h2Transport http.Transport
	h1h2Client    http.Client
	trySocks5     bool
	tryH3Direct   bool
}

func NewHttpClient() HTTPClient {
	client := new(httpClient)
	client.h1h2Client.Transport = &client.h1h2Transport
	client.h1h2Transport.TLSClientConfig = &client.tls
	client.h1h2Transport.DisableKeepAlives = true
	// S-B3: whole-request timeout. Without it a stalled fetch (dead
	// proxy, hung server) hangs forever and the caller never learns
	// it failed. On timeout Do returns a clear "Client.Timeout
	// exceeded" error. Callers may override via SetTimeout.
	client.h1h2Client.Timeout = 30 * time.Second
	return client
}

func (c *httpClient) ModernTLS() {
	c.tls.MinVersion = tls.VersionTLS12
	// （曾在此把 CipherSuites 映射为 ID 列表，已弃用。）
}

func (c *httpClient) RestrictedTLS() {
	c.tls.MinVersion = tls.VersionTLS13
	// （曾在此过滤出支持 TLS1.3 的 CipherSuite 再映射为 ID 列表，已弃用。）
}

func (c *httpClient) PinnedTLS12() {
	c.tls.MinVersion = tls.VersionTLS12
	c.tls.MaxVersion = tls.VersionTLS12
}

func (c *httpClient) PinnedSHA256(sumHex string) {
	c.tls.VerifyPeerCertificate = func(rawCerts [][]byte, verifiedChains [][]*x509.Certificate) error {
		for _, rawCert := range rawCerts {
			certSum := sha256.Sum256(rawCert)
			if sumHex == hex.EncodeToString(certSum[:]) {
				return nil
			}
		}
		return errors.New("pinned sha256 sum mismatch")
	}
}

// SetTimeout sets the whole-request timeout in milliseconds for
// this client (S-B3). NewHttpClient defaults to 30s: without it a
// stalled subscription fetch could hang forever, since the transport
// has no dial/read timeouts of its own. 0 disables the timeout.
// doH3Direct does not use this client and keeps its own 10s bound.
func (c *httpClient) SetTimeout(timeoutMs int32) {
	c.h1h2Client.Timeout = time.Duration(timeoutMs) * time.Millisecond
}

func (c *httpClient) TrySocks5(port int32) {
	dialer := new(net.Dialer)
	c.h1h2Transport.DialContext = func(ctx context.Context, network, addr string) (net.Conn, error) {
		socksConn, err := dialer.DialContext(ctx, "tcp", "127.0.0.1:"+strconv.Itoa(int(port)))
		if err != nil {
			if c.tryH3Direct {
				return nil, errFailConnectSocks5
			}
			return nil, err
		}
		_, err = socks.ClientHandshake5(socksConn, socks5.CommandConnect, metadata.ParseSocksaddr(addr), "", "")
		if err != nil {
			socksConn.Close()
			if c.tryH3Direct {
				return nil, errFailConnectSocks5
			}
			return nil, err
		}
		return socksConn, err
	}
	c.trySocks5 = true
}

// TrySocks5Auth is TrySocks5 with SOCKS5 username/password auth (0.2.29 -26).
// Same dialer, but the handshake carries the mixed inbound credentials.
func (c *httpClient) TrySocks5Auth(port int32, username string, password string) {
	dialer := new(net.Dialer)
	c.h1h2Transport.DialContext = func(ctx context.Context, network, addr string) (net.Conn, error) {
		socksConn, err := dialer.DialContext(ctx, "tcp", "127.0.0.1:"+strconv.Itoa(int(port)))
		if err != nil {
			if c.tryH3Direct {
				return nil, errFailConnectSocks5
			}
			return nil, err
		}
		_, err = socks.ClientHandshake5(socksConn, socks5.CommandConnect, metadata.ParseSocksaddr(addr), username, password)
		if err != nil {
			socksConn.Close()
			if c.tryH3Direct {
				return nil, errFailConnectSocks5
			}
			return nil, err
		}
		return socksConn, err
	}
	c.trySocks5 = true
}

// TryBoxOutbound 经当前 main box 默认 outbound 拨号。
// 用于 disableMixedInbound（纯 TUN）场景：VPN 应用自身流量默认不进 tun，
// 不能再走 127.0.0.1:mixedPort（入站已不存在），必须显式经节点出站。
// box 未运行或无默认出站时返回错误，绝不静默回落到直连：订阅拉取
// 走真实 IP 且调用方不知情，比失败更糟。
func (c *httpClient) TryBoxOutbound() {
	c.h1h2Transport.DialContext = func(ctx context.Context, network, addr string) (net.Conn, error) {
		b := mainInstance.Load()
		if b == nil || b.Box == nil {
			return nil, errors.New("box not running")
		}
		outbound := b.Outbound().Default()
		if outbound == nil {
			return nil, errors.New("no default outbound")
		}
		return outbound.DialContext(ctx, network, metadata.ParseSocksaddr(addr))
	}
}

func (c *httpClient) TryH3Direct() {
	c.tryH3Direct = true
}

func (c *httpClient) KeepAlive() {
	c.h1h2Transport.ForceAttemptHTTP2 = true
	c.h1h2Transport.DisableKeepAlives = false
}

func (c *httpClient) NewRequest() HTTPRequest {
	req := &httpRequest{httpClient: c}
	req.request = http.Request{
		Method: "GET",
		Header: http.Header{},
	}
	return req
}

func (c *httpClient) Close() {
	c.h1h2Transport.CloseIdleConnections()
}

type httpRequest struct {
	*httpClient
	request http.Request
}

func (r *httpRequest) AllowInsecure() {
	r.tls.InsecureSkipVerify = true
}

func (r *httpRequest) SetURL(link string) (err error) {
	r.request.URL, err = url.Parse(link)
	if err != nil {
		return
	}
	if r.request.URL.User != nil {
		user := r.request.URL.User.Username()
		password, _ := r.request.URL.User.Password()
		r.request.SetBasicAuth(user, password)
	}
	return
}

func (r *httpRequest) SetMethod(method string) {
	r.request.Method = method
}

func (r *httpRequest) SetHeader(key string, value string) {
	r.request.Header.Set(key, value)
}

func (r *httpRequest) SetUserAgent(userAgent string) {
	r.request.Header.Set("User-Agent", userAgent)
}

func (r *httpRequest) SetContent(content []byte) {
	buffer := bytes.Buffer{}
	buffer.Write(content)
	r.request.Body = io.NopCloser(bytes.NewReader(buffer.Bytes()))
	r.request.ContentLength = int64(len(content))
}

func (r *httpRequest) SetContentString(content string) {
	r.SetContent([]byte(content))
}

func (r *httpRequest) Execute() (HTTPResponse, error) {
	defer device.DeferPanicToError("http execute", func(err error) { log.Println(err) })
	// full direct
	if r.tryH3Direct && !r.trySocks5 {
		return r.doH3Direct()
	}
	response, err := r.h1h2Client.Do(&r.request)
	if err != nil {
		log.Println("http execute via socks5 failed:", err)
		// trySocks5 && tryH3Direct
		if r.tryH3Direct && errors.Is(err, errFailConnectSocks5) {
			log.Println("http execute: socks5 unavailable, falling back to H3 direct")
			return r.doH3Direct()
		}
		return nil, err
	}
	httpResp := &httpResponse{Response: response}
	if response.StatusCode >= 400 {
		return nil, errors.New(httpResp.errorString())
	}
	return httpResp, nil
}

type requestFunc func() (response *http.Response, err error)

// transportClosingBody ties a per-request transport's lifetime to
// the response body: doH3Direct creates a fresh transport per call,
// and without this the http3.Transport (and the ECH http.Transport's
// idle connections) leaked on every call. The transport is closed
// when the caller closes the body; Close is idempotent.
type transportClosingBody struct {
	io.ReadCloser
	closeFn func()
	once    sync.Once
}

func (b *transportClosingBody) Close() error {
	err := b.ReadCloser.Close()
	b.once.Do(b.closeFn)
	return err
}

func (r *httpRequest) doH3Direct() (HTTPResponse, error) {
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	successCh := make(chan *http.Response, 1)
	var finalErr error
	var failedCount atomic.Uint32
	var successCount atomic.Uint32
	var mu sync.Mutex

	funcs := []requestFunc{
		// Http(s) With Ech
		func() (response *http.Response, err error) {
			// Per-request timeout ctx, independent of the outer
			// wait ctx: cancelling the outer ctx on return must not
			// kill the winning response body, and a hung server must
			// not hold this goroutine forever (old code cloned
			// context.Background(): unbounded).
			reqCtx, reqCancel := context.WithTimeout(context.Background(), 10*time.Second)
			request := r.request.Clone(reqCtx)
			transport := &http.Transport{
				DialTLSContext: func(ctx context.Context, network, addr string) (net.Conn, error) {
					var d net.Dialer
					c, err := d.DialContext(ctx, network, addr)
					if err != nil {
						return c, err
					}
					domain := addr
					if host, _, _ := net.SplitHostPort(addr); host != "" {
						domain = host
					}
					echTls := ech.NewECHClientConfig(domain, &r.tls, gLocalDNSTransport)
					return echTls.Client(ctx, c)
				},
				DisableKeepAlives: true,
			}
			echClient := &http.Client{Transport: transport}
			resp, err := echClient.Do(request)
			if err != nil {
				reqCancel()
				transport.CloseIdleConnections()
				return nil, err
			}
			resp.Body = &transportClosingBody{
				ReadCloser: resp.Body,
				closeFn: func() {
					reqCancel()
					transport.CloseIdleConnections()
				},
			}
			return resp, nil
		},
		// H3 HTTPS
		func() (response *http.Response, err error) {
			reqCtx, reqCancel := context.WithTimeout(context.Background(), 10*time.Second)
			request := r.request.Clone(reqCtx)
			h3Transport := &http3.Transport{
				TLSClientConfig: r.tls.Clone(),
				QUICConfig: &quic.Config{
					// 与 doH3Direct 整体 10s 超时保持一致，避免 QUIC 空闲超时(1s)过早触发
					// 导致 "no recent network activity" 误报订阅更新失败
					MaxIdleTimeout: 10 * time.Second,
				},
			}
			h3Client := &http.Client{Transport: h3Transport}
			resp, err := h3Client.Do(request)
			if err != nil {
				reqCancel()
				_ = h3Transport.Close()
				return nil, err
			}
			resp.Body = &transportClosingBody{
				ReadCloser: resp.Body,
				closeFn: func() {
					reqCancel()
					_ = h3Transport.Close()
				},
			}
			return resp, nil
		},
	}

	if r.request.URL.Scheme == "http" {
		funcs = funcs[:1]
	}

	for i, f := range funcs {
		go func(f requestFunc) {
			defer device.DeferPanicToError("http", func(err error) { log.Println(err) })
			defer func() {
				if successCount.Load() == 0 {
					if failedCount.Add(1) >= uint32(len(funcs)) {
						// 全部失败了
						cancel()
					}
				}
			}()

			var t string
			switch i {
			case 0:
				t = "http(s)"
			case 1:
				t = "h3"
			}

			// 执行HTTP请求
			rsp, err := f()
			if rsp == nil || err != nil {
				log.Println("h3 direct request", t, "failed:", err)
				mu.Lock()
				finalErr = errors.Join(finalErr, fmt.Errorf("%s: %w", t, err))
				mu.Unlock()
				if rsp != nil && rsp.Body != nil {
					rsp.Body.Close()
				}
				return
			}

			// 处理 HTTP 状态码
			if rsp.StatusCode >= 400 {
				hr := &httpResponse{Response: rsp}
				err = fmt.Errorf("%s: %s", t, hr.errorString())
				mu.Lock()
				finalErr = errors.Join(finalErr, err)
				mu.Unlock()
				// errorString already read+closed the body; this
				// is belt-and-braces (idempotent) and releases the
				// transport via the body wrapper.
				rsp.Body.Close()
				return
			}

			select {
			case successCh <- rsp:
				// 第一个成功的请求，不要关闭 body
				successCount.Add(1)
			default:
				rsp.Body.Close()
			}
		}(f)
	}

	select {
	case result := <-successCh:
		return &httpResponse{Response: result}, nil
	case <-ctx.Done():
		// Never return (nil, nil): on a bare timeout with no
		// recorded failure, report the context error so Kotlin
		// callers (RawUpdater, NetworkFragment) get a real error
		// instead of an NPE at the gomobile boundary.
		if finalErr == nil {
			finalErr = ctx.Err()
		}
		return nil, finalErr
	}
}

type httpResponse struct {
	*http.Response

	getContentOnce sync.Once
	content        []byte
	contentError   error
}

func (h *httpResponse) errorString() string {
	content, err := h.getContentString()
	if err != nil {
		return fmt.Sprint("HTTP ", h.Status)
	}
	if len(content) > 100 {
		content = content[:100] + " ..."
	}
	return fmt.Sprint("HTTP ", h.Status, ": ", content)
}

func (h *httpResponse) GetHeader(key string) *StringBox {
	return wrapString(h.Header.Get(key))
}

func (h *httpResponse) GetContent() ([]byte, error) {
	h.getContentOnce.Do(func() {
		defer h.Body.Close()
		h.content, h.contentError = io.ReadAll(h.Body)
	})
	return h.content, h.contentError
}

func (h *httpResponse) GetContentString() (*StringBox, error) {
	content, err := h.getContentString()
	if err != nil {
		return nil, err
	}
	return wrapString(content), nil
}

func (h *httpResponse) getContentString() (string, error) {
	content, err := h.GetContent()
	if err != nil {
		return "", err
	}
	return string(content), nil
}

func (h *httpResponse) WriteTo(path string) error {
	defer h.Body.Close()
	file, err := os.Create(path)
	if err != nil {
		return err
	}
	defer file.Close()
	_, err = io.Copy(file, h.Body)
	return err
}
