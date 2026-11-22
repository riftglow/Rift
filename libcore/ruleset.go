package libcore

// Local rule-sets (2026-10-31 migration).
//
// Routing no longer uses geoip.db / geosite.db, and the old db->.srs
// conversion shim is gone. The Kotlin side now emits local rule-sets that
// point straight at real .srs files in the external assets directory
// (bundled files extracted at install, or files the user downloaded from
// the routing-assets page). This file only *validates* those files before
// the kernel loads them:
//
//   - a referenced .srs that is missing, empty, or fails a full srs.Read
//     parse first falls back to a versioned backup
//     ("<name>.bak-<YYYY-MM-DD>-<sha>", newest first, then a legacy
//     "<name>.bak" if one exists);
//   - if no backup parses either, startup fails loudly with a clear
//     error. There is deliberately NO empty-rule fallback: the previous
//     empty .srs placeholder made a failed download look like "no rules",
//     silently sending domestic traffic through the proxy for up to 24h.
//     A loud failure beats a silent wrong route.
//
// A missing file with no usable backup triggers one synchronous
// extractAssets() attempt first, because the background extraction
// goroutine in InitCore may not have finished yet on a fresh install.

import (
	"encoding/json"
	"fmt"
	"log"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"time"

	"github.com/sagernet/sing-box/common/srs"
	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/option"
)

// checkLocalRuleSets validates every local rule-set file in the config.
// It rewrites rs.LocalOptions.Path to a usable backup when the primary
// file is unusable, and returns an error when nothing usable exists.
// Remote rule-sets are rejected loudly: Rift's migration is local-only,
// and a remote rule-set would silently reintroduce the kernel updater
// path (startup fetch, 24h retry) that the migration removed.
func checkLocalRuleSets(ruleSets []option.RuleSet) error {
	extractedOnce := false
	for i := range ruleSets {
		rs := &ruleSets[i]
		if rs.Type == C.RuleSetTypeRemote {
			return fmt.Errorf("rule-set %v: remote rule-sets are not supported by Rift (local .srs only); remove the remote rule-set or replace it with a local .srs file", rs.Tag)
		}
		if rs.Type != C.RuleSetTypeLocal {
			continue
		}
		path := rs.LocalOptions.Path
		if path == "" {
			continue
		}
		format := ruleSetFormat(rs)
		tag := ruleSetTagLabel(rs)
		if usableSRS(path, format) {
			clearFallbackStatus(tag, filepath.Dir(path))
			continue
		}
		// H3: try a versioned backup FIRST. Extracting the bundled
		// asset before this silently downgraded a newer downloaded
		// primary to the older bundled version and rewrote the
		// version file, without even logging it.
		if bak, ok := usableBackup(path, format); ok {
			log.Printf("rule-set %v: primary file %s unusable, falling back to backup %s", rs.Tag, path, bak)
			recordFallbackStatus(tag, path, bak)
			rs.LocalOptions.Path = bak
			continue
		}
		// No usable backup: restore the bundled file, at most once
		// per pass. A missing file also triggers one synchronous
		// extractAssets() attempt here, because the background
		// extraction goroutine in InitCore may not have finished
		// yet on a fresh install.
		if !extractedOnce {
			extractedOnce = true
			extractAssets()
		}
		if usableSRS(path, format) {
			log.Printf("rule-set %v: primary file %s unusable and no backup exists, restored bundled version", rs.Tag, path)
			clearFallbackStatus(tag, filepath.Dir(path))
			continue
		}
		return fmt.Errorf("rule-set %v: .srs file %s is missing, empty, or corrupt, and no usable backup exists; open the routing-assets page, update the rule-set, and reconnect", rs.Tag, path)
	}
	return nil
}

// ruleSetTagLabel renders a rule-set's tag list as a single string
// for log/status-file lines, without assuming the tag list's concrete
// slice type.
func ruleSetTagLabel(rs *option.RuleSet) string {
	label := ""
	for i, t := range rs.Tag {
		if i > 0 {
			label += ","
		}
		label += t
	}
	return label
}

// ruleSetFormat resolves the format to validate against. An explicit
// format wins; otherwise the extension decides (.srs -> binary,
// .json -> source). An unknown extension returns "" (matching upstream
// option/rule_set.go: the kernel then reports "missing format" at parse
// time), it is NOT silently treated as binary.
func ruleSetFormat(rs *option.RuleSet) string {
	if rs.Format != "" {
		return rs.Format
	}
	switch filepath.Ext(rs.LocalOptions.Path) {
	case ".json":
		return C.RuleSetFormatSource
	case ".srs":
		return C.RuleSetFormatBinary
	default:
		return ""
	}
}

// usableSRS reports whether path exists, is non-empty, and parses in
// its format: binary .srs files go through a full srs.Read parse,
// source (JSON) rule-sets must parse as a JSON object (B7). A binary
// parse is never applied to a JSON file, or vice versa.
func usableSRS(path string, format string) bool {
	info, err := os.Stat(path)
	if err != nil || info.Size() == 0 {
		return false
	}
	if format == C.RuleSetFormatSource {
		data, err := os.ReadFile(path)
		if err != nil {
			return false
		}
		var obj map[string]any
		if err := json.Unmarshal(data, &obj); err != nil {
			return false
		}
		// 任何能解析成 JSON 对象的规则集都有效，包括空对象 {}（内核
		// 接受它为空规则集）；不要用 len(obj) > 0 拒绝它。
		return true
	}
	f, err := os.Open(path)
	if err != nil {
		return false
	}
	defer f.Close()
	_, err = srs.Read(f, false)
	return err == nil
}

// fallbackStatusFile is the least-invasive channel that makes a
// backup fallback visible beyond logcat (A5): one line per rule-set
// tag, in the same directory as the rule-set files, so the
// routing-assets page (or any future UI) can read and display it
// without a new screen. It is best-effort — a write failure never
// blocks startup — and a tag's line is cleared as soon as its primary
// file parses again.
const fallbackStatusFile = "rule-set-fallback.txt"

// uniqueTmpName derives a per-call tmp name from the destination, so
// two writers (main process and :bg process) can never share one tmp
// file. Used by the rule-set status files and the asset extractor.
func uniqueTmpName(dst string) string {
	return fmt.Sprintf("%s.tmp-%d-%d", dst, os.Getpid(), time.Now().UnixNano())
}

// recordFallbackStatus writes one fallback record into the status file.
// NOTE: read-modify-write without a cross-process lock. The main process
// and the :bg process both write this file; two concurrent writes race
// and the last rename wins, dropping the other process's record. Only
// one status line is lost (the fallback itself is unaffected — the path
// rewrite lives in each instance's memory), so this is accepted as a
// known limitation, not fixed with a lock.
func recordFallbackStatus(tag, primary, backup string) {
	if tag == "" {
		return
	}
	dir := filepath.Dir(primary)
	statusPath := filepath.Join(dir, fallbackStatusFile)
	lines := []string{}
	if data, err := os.ReadFile(statusPath); err == nil {
		for _, line := range strings.Split(string(data), "\n") {
			if line == "" || strings.HasPrefix(line, tag+" ") {
				continue
			}
			lines = append(lines, line)
		}
	}
	lines = append(lines, tag+" "+primary+" -> "+backup)
	tmp := uniqueTmpName(statusPath)
	if err := os.WriteFile(tmp, []byte(strings.Join(lines, "\n")+"\n"), 0644); err != nil {
		return
	}
	if err := os.Rename(tmp, statusPath); err != nil {
		_ = os.Remove(tmp)
	}
}

func clearFallbackStatus(tag, dir string) {
	if tag == "" {
		return
	}
	statusPath := filepath.Join(dir, fallbackStatusFile)
	data, err := os.ReadFile(statusPath)
	if err != nil {
		return
	}
	lines := []string{}
	changed := false
	for _, line := range strings.Split(string(data), "\n") {
		if line == "" {
			continue
		}
		if strings.HasPrefix(line, tag+" ") {
			changed = true
			continue
		}
		lines = append(lines, line)
	}
	if !changed {
		return
	}
	if len(lines) == 0 {
		_ = os.Remove(statusPath)
		return
	}
	tmp := uniqueTmpName(statusPath)
	if err := os.WriteFile(tmp, []byte(strings.Join(lines, "\n")+"\n"), 0644); err != nil {
		return
	}
	if err := os.Rename(tmp, statusPath); err != nil {
		_ = os.Remove(tmp)
	}
}

// usableBackup finds a usable versioned backup for path, newest first.
// Backups are named "<name>.bak-<YYYY-MM-DD>-<sha>", but the sort key
// is the file's modification time (B5), not the name: same-day backups
// differ only by sha, and a lexicographic sha order is not a recency
// order. A legacy plain "<name>.bak" is tried last.
func usableBackup(path string, format string) (string, bool) {
	dir := filepath.Dir(path)
	base := filepath.Base(path)
	entries, err := os.ReadDir(dir)
	if err != nil {
		return "", false
	}
	type candidate struct {
		path    string
		modTime time.Time
	}
	var candidates []candidate
	for _, e := range entries {
		name := e.Name()
		if strings.HasPrefix(name, base+".bak-") {
			full := filepath.Join(dir, name)
			info, err := os.Stat(full)
			if err != nil {
				continue
			}
			candidates = append(candidates, candidate{path: full, modTime: info.ModTime()})
		}
	}
	sort.Slice(candidates, func(i, j int) bool {
		if !candidates[i].modTime.Equal(candidates[j].modTime) {
			return candidates[i].modTime.After(candidates[j].modTime)
		}
		return candidates[i].path > candidates[j].path
	})
	for _, c := range candidates {
		if usableSRS(c.path, format) {
			return c.path, true
		}
	}
	legacy := path + ".bak"
	if usableSRS(legacy, format) {
		return legacy, true
	}
	return "", false
}
