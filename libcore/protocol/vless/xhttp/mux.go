package xhttp

import (
	"context"
	"crypto/rand"
	"math"
	"math/big"
	"sync"
	"sync/atomic"
	"time"
)

type XmuxConn interface {
	IsClosed() bool
	Close() error
}

type XmuxClient struct {
	XmuxConn     XmuxConn
	OpenUsage    atomic.Int32
	leftUsage    int32
	LeftRequests atomic.Int32
	UnreusableAt time.Time
}

type XmuxManager struct {
	options     V2RayXHTTPXmuxOptions
	concurrency int32
	connections int32
	newConnFunc func() XmuxConn
	xmuxClients []*XmuxClient
	mtx         sync.Mutex
}

func NewXmuxManager(options V2RayXHTTPXmuxOptions, newConnFunc func() XmuxConn) *XmuxManager {
	return &XmuxManager{
		options:     options,
		concurrency: options.GetNormalizedMaxConcurrency().Rand(),
		connections: options.GetNormalizedMaxConnections().Rand(),
		newConnFunc: newConnFunc,
		xmuxClients: make([]*XmuxClient, 0),
	}
}

func (m *XmuxManager) newXmuxClient() *XmuxClient {
	xmuxClient := &XmuxClient{
		XmuxConn:  m.newConnFunc(),
		leftUsage: -1,
	}
	if x := m.options.GetNormalizedCMaxReuseTimes().Rand(); x > 0 {
		xmuxClient.leftUsage = x - 1
	}
	xmuxClient.LeftRequests.Store(math.MaxInt32)
	if x := m.options.GetNormalizedHMaxRequestTimes().Rand(); x > 0 {
		xmuxClient.LeftRequests.Store(x)
	}
	if x := m.options.GetNormalizedHMaxReusableSecs().Rand(); x > 0 {
		xmuxClient.UnreusableAt = time.Now().Add(time.Duration(x) * time.Second)
	}
	m.xmuxClients = append(m.xmuxClients, xmuxClient)
	return xmuxClient
}

// GetXmuxClient picks a client and reserves it: picking and marking
// in-use happen in one step under the lock, otherwise two concurrent
// dials can both pick the same client before either increments
// OpenUsage and the concurrency limit is overshot. The caller must
// release the reservation with OpenUsage.Add(-1) when done.
func (m *XmuxManager) GetXmuxClient(ctx context.Context) *XmuxClient {
	xmuxClient := m.pickXmuxClient()
	xmuxClient.OpenUsage.Add(1)
	return xmuxClient
}

// GetXmuxClientNoReserve picks a client without reserving it, for
// the upload loop's mid-session transport switch: the session's
// usage is already counted on the client picked at dial time, and
// a switched-in client must not gain a reservation that onClose
// would never release.
func (m *XmuxManager) GetXmuxClientNoReserve(ctx context.Context) *XmuxClient {
	return m.pickXmuxClient()
}

// pickXmuxClient is the shared selection: prune dead/exhausted
// clients, create a fresh one when the pool is empty or under the
// connection limit, else pick a random one under the concurrency
// limit. The caller decides whether the pick reserves a usage slot.
func (m *XmuxManager) pickXmuxClient() *XmuxClient {
	m.mtx.Lock()
	defer m.mtx.Unlock()
	for i := 0; i < len(m.xmuxClients); {
		xmuxClient := m.xmuxClients[i]
		if xmuxClient.XmuxConn.IsClosed() ||
			xmuxClient.leftUsage == 0 ||
			xmuxClient.LeftRequests.Load() <= 0 ||
			(xmuxClient.UnreusableAt != time.Time{} && time.Now().After(xmuxClient.UnreusableAt)) {
			_ = xmuxClient.XmuxConn.Close()
			m.xmuxClients = append(m.xmuxClients[:i], m.xmuxClients[i+1:]...)
		} else {
			i++
		}
	}
	if len(m.xmuxClients) == 0 {
		return m.newXmuxClient()
	}
	if m.connections > 0 && len(m.xmuxClients) < int(m.connections) {
		return m.newXmuxClient()
	}
	xmuxClients := make([]*XmuxClient, 0)
	if m.concurrency > 0 {
		for _, xmuxClient := range m.xmuxClients {
			if xmuxClient.OpenUsage.Load() < m.concurrency {
				xmuxClients = append(xmuxClients, xmuxClient)
			}
		}
	} else {
		xmuxClients = m.xmuxClients
	}
	if len(xmuxClients) == 0 {
		return m.newXmuxClient()
	}
	// crypto/rand 失败极罕见（getrandom 在应用进程运行时基本不会失败），
	// 但一旦失败 i 为 nil，直接取 i.Int64() 会 panic。失败时回落到第 0 个。
	idx := int64(0)
	if i, err := rand.Int(rand.Reader, big.NewInt(int64(len(xmuxClients)))); err == nil && i != nil {
		idx = i.Int64()
	}
	xmuxClient := xmuxClients[idx]
	if xmuxClient.leftUsage > 0 {
		xmuxClient.leftUsage -= 1
	}
	return xmuxClient
}

// Reset is the upstream-style reset used by Client.Close on interface
// updates: it closes only idle (unused) connections and drops them
// from the pool, keeping the manager usable. In-flight sessions keep
// working on their existing connections, and the next dial creates
// fresh connections on the new network.
func (m *XmuxManager) Reset() {
	m.mtx.Lock()
	defer m.mtx.Unlock()
	kept := make([]*XmuxClient, 0, len(m.xmuxClients))
	for _, client := range m.xmuxClients {
		if client != nil && client.XmuxConn != nil && client.OpenUsage.Load() == 0 {
			_ = client.XmuxConn.Close()
			continue
		}
		kept = append(kept, client)
	}
	m.xmuxClients = kept
}
