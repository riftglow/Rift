package libcore

import (
	"crypto/x509"
	"log"
	_ "unsafe" // for go:linkname
)

//go:linkname systemRoots crypto/x509.systemRoots
var systemRoots *x509.CertPool

// updateRootCACerts merges an external ca.pem into the system root
// pool instead of replacing it (H14): the old code built a pool from
// the pem alone, so a ca.pem in the external assets directory silently
// dropped every system root and broke all public TLS handshakes.
// SystemCertPool returns a clone, so appending to it never mutates
// the shared pool; assigning the merged clone installs it.
// Must run synchronously during InitCore, before any box or TLS use:
// the assignment races with concurrent SystemCertPool reads if it
// happens in a background goroutine.
func updateRootCACerts(pem []byte) {
	system, err := x509.SystemCertPool()
	if err != nil || system == nil {
		log.Println("failed to load system cert pool, using custom pool only:", err)
		system = x509.NewCertPool()
	}
	if !system.AppendCertsFromPEM(pem) {
		log.Println("failed to append certificates from pem")
		return
	}
	systemRoots = system
	log.Println("external ca.pem was merged into system roots")
}
