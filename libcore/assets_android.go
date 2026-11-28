//go:build android

package libcore

import (
	"fmt"
	"io"
	"log"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"

	"golang.org/x/mobile/asset"
)

// extractMu serialises all asset extraction in this process (A6).
// Extraction previously ran with no lock and a fixed "<dst>.tmp" name:
// InitCore's background goroutine and checkLocalRuleSets' synchronous
// call (on every new instance, including speed-test instances) could
// write the same tmp file at once. The mutex alone would fix that,
// but unique tmp names (below) make the install step safe even if a
// future caller bypasses the lock.
var extractMu sync.Mutex

func extractAssets() {
	extractMu.Lock()
	defer extractMu.Unlock()

	extractSRSAssets()

	extract := func(name string) {
		err := extractAssetName(name)
		if err != nil {
			log.Println("Extract", name, "failed:", err)
		}
	}

	extract(yacdDstFolder)
}

// writeFileAtomic writes data via a unique tmp file followed by
// Rename (B10), so a crash mid-write cannot leave a half-written
// version file that the next start mistakes for current.
func writeFileAtomic(path string, data []byte) error {
	tmp := uniqueTmpName(path)
	if err := os.WriteFile(tmp, data, 0644); err != nil {
		_ = os.Remove(tmp)
		return err
	}
	if err := os.Rename(tmp, path); err != nil {
		_ = os.Remove(tmp)
		return err
	}
	return nil
}

// ("<YYYY-MM-DD> <short-sha>") into YYYYMMDD for ordering. ok=false when
// the version string has no parseable date (e.g. "Custom" is handled by
// the caller before this is reached).
func srsVersionDate(version string) (uint64, bool) {
	fields := strings.Fields(strings.TrimSpace(version))
	if len(fields) == 0 {
		return 0, false
	}
	digits := strings.ReplaceAll(fields[0], "-", "")
	if len(digits) != 8 {
		return 0, false
	}
	v, err := strconv.ParseUint(digits, 10, 64)
	return v, err == nil
}

// srsVersionDownloaded reports whether a local version string was stamped
// by a manual download (AssetsActivity.updateSrsFile writes
// "<YYYY-MM-DD> <short-sha> downloaded"). Such files always win over the
// bundled ones and are never overwritten by a newer APK.
func srsVersionDownloaded(version string) bool {
	fields := strings.Fields(strings.TrimSpace(version))
	return len(fields) >= 3 && fields[2] == "downloaded"
}

// extractSRSAssets extracts the bundled .srs rule-sets to the external
// assets directory. Unlike the old geoip/geosite db files, .srs files are
// stored raw in the APK (no xz step) and are versioned per file by
// "<YYYY-MM-DD> <short-sha>".
//
// Upgrade rule (2026-10-31 decision: the bundled copy is only the initial
// version; updates come from the route resource update, and a manually
// downloaded file always wins over the bundled one):
//   - file missing, or its version file missing -> extract;
//   - local version "Custom" (user-imported file) -> never overwrite;
//   - local version marked "downloaded" (manually downloaded via the
//     route resource update; stamped by AssetsActivity.updateSrsFile)
//     -> never overwrite;
//   - damaged local file (fails a full parse) -> re-extract, whatever
//     the dates say (A5: a version hit is not an acceptance);
//   - otherwise -> extract only when the bundled date is newer than
//     the local date, so a newer manual update is never downgraded
//     (same-day content wins on a tie; unparseable versions that are
//     string-equal also win on a tie).
// The version file is written only after a successful copy, so a failed
// copy retries on the next start instead of being mistaken for current.
func extractSRSAssets() {
	for _, name := range bundledSRS {
		if err := extractSRSAsset(name); err != nil {
			log.Println("Extract", name, "failed:", err)
		}
	}
}

func extractSRSAsset(name string) error {
	dstName := externalAssetsPath + name
	versionName := strings.TrimSuffix(name, ".srs") + ".version.txt"

	// Bundled version from the APK.
	av, err := asset.Open(apkAssetPrefixSingBox + versionName)
	if err != nil {
		return fmt.Errorf("open version in assets: %v", err)
	}
	b, err := io.ReadAll(av)
	av.Close()
	if err != nil {
		return fmt.Errorf("read internal version: %v", err)
	}
	assetVersion := string(b)

	if _, err := os.Stat(dstName); err == nil {
		localVersionBytes, err := os.ReadFile(externalAssetsPath + versionName)
		if err == nil {
			localVersion := strings.TrimSpace(string(localVersionBytes))
			if localVersion == "Custom" {
				return nil
			}
			// Manually downloaded via the route resource update:
			// downloaded rules always win over the bundled ones, so
			// a newer APK never overwrites them (2026-10-31 decision;
			// the marker is stamped by AssetsActivity.updateSrsFile).
			// Unmarked pre--79 version files fall back to the date
			// comparison below (one-time edge: a pre--79 manual
			// download older than the -79 bundled snapshot can be
			// overwritten once by a newer same-repo snapshot).
			if srsVersionDownloaded(localVersion) {
				return nil
			}
			// A5: a version hit is not an acceptance. A damaged
			// primary file whose version file still looks current
			// must never be accepted and left to rot — a failed full
			// parse forces a fresh extraction below, whatever the
			// dates say. (Custom files and downloaded files are
			// returned above and never overwritten; for those,
			// checkLocalRuleSets handles damage via backup/loud
			// failure instead.)
			if !usableSRS(dstName, "binary") {
				// Fall through to extraction.
			} else {
				bundledDate, bOk := srsVersionDate(assetVersion)
				localDate, lOk := srsVersionDate(localVersion)
				if bOk && lOk {
					if bundledDate <= localDate {
						return nil
					}
				} else if strings.TrimSpace(assetVersion) == localVersion {
					return nil
				}
			}
		}
		// Missing version file (or unparseable versions that differ):
		// fall through and extract.
	}

	f, err := asset.Open(apkAssetPrefixSingBox + name)
	if err != nil {
		return fmt.Errorf("open %s in assets: %v", name, err)
	}
	tmpName := uniqueTmpName(dstName)
	if err := extractAsset(f, tmpName); err != nil {
		_ = os.Remove(tmpName)
		return fmt.Errorf("extract %s: %v", name, err)
	}
	if err := os.Rename(tmpName, dstName); err != nil {
		_ = os.Remove(tmpName)
		return fmt.Errorf("install %s: %v", name, err)
	}
	if err := writeFileAtomic(externalAssetsPath+versionName, []byte(assetVersion)); err != nil {
		return fmt.Errorf("write version for %s: %v", name, err)
	}
	return nil
}

// 这里解压的是 apk 里面的
func extractAssetName(name string) error {
	// 支持非官方源的，就是 replaceable，放 Android 目录
	// 不支持非官方源的，就放 file 目录
	replaceable := true

	var version string
	var apkPrefix string
	switch name {
	case yacdDstFolder:
		version = yacdVersion
		replaceable = false
	default:
		return fmt.Errorf("extractAssetName: unsupported asset %s", name)
	}

	var dir string
	if !replaceable {
		dir = internalAssetsPath
	} else {
		dir = externalAssetsPath
	}
	dstName := dir + name

	var localVersion string
	var assetVersion string

	// loadAssetVersion from APK
	loadAssetVersion := func() error {
		av, err := asset.Open(apkPrefix + version)
		if err != nil {
			return fmt.Errorf("open version in assets: %v", err)
		}
		b, err := io.ReadAll(av)
		av.Close()
		if err != nil {
			return fmt.Errorf("read internal version: %v", err)
		}
		assetVersion = string(b)
		return nil
	}
	if err := loadAssetVersion(); err != nil {
		return err
	}

	var doExtract bool

	if _, err := os.Stat(dstName); err != nil {
		// assetFileMissing
		doExtract = true
	} else if !replaceable {
		// Non-replaceable asset (yacd): check the bundled version
		// and re-extract when it is newer or the local one is bad.
		b, err := os.ReadFile(dir + version)
		if err != nil {
			// versionFileMissing
			doExtract = true
			_ = os.RemoveAll(dir + version)
		} else {
			localVersion = string(b)
			if localVersion == "Custom" {
				doExtract = false
			} else {
				av, err := strconv.ParseUint(assetVersion, 10, 64)
				if err != nil {
					doExtract = assetVersion != localVersion
				} else {
					lv, err := strconv.ParseUint(localVersion, 10, 64)
					doExtract = err != nil || av > lv
				}
			}
		}
	} else {
		//非官方源不升级
	}

	if !doExtract {
		return nil
	}

	extractXz := func(f asset.File) error {
		tmpXzName := uniqueTmpName(dstName + ".xz")
		err := extractAsset(f, tmpXzName)
		if err == nil {
			err = Unxz(tmpXzName, dstName)
			os.Remove(tmpXzName)
		}
		if err != nil {
			return fmt.Errorf("extract xz: %v", err)
		}
		return nil
	}

	extracZip := func(f asset.File, outDir string) error {
		tmpZipName := uniqueTmpName(dstName + ".zip")
		err := extractAsset(f, tmpZipName)
		if err == nil {
			err = Unzip(tmpZipName, outDir)
			os.Remove(tmpZipName)
		}
		if err != nil {
			return fmt.Errorf("extract zip: %v", err)
		}
		return nil
	}

	// 解压失败必须返回错误，且不写版本号，否则下次启动以为已是新版、
	// 不再重解压。yacd fallback 只属于 yacd 本身。
	var extractErr error
	if f, err := asset.Open(apkPrefix + name + ".xz"); err == nil {
		extractErr = extractXz(f)
	} else if name == yacdDstFolder {
		if f, err := asset.Open("yacd.zip"); err == nil {
			os.RemoveAll(dstName)
			// A crashed previous run may have left a partial
			// Yacd-* directory behind; the glob below expects
			// exactly one match, so clear stale ones first or
			// extraction fails forever with "expect 1".
			if stale, _ := filepath.Glob(internalAssetsPath + "/Yacd-*"); len(stale) > 0 {
				for _, s := range stale {
					os.RemoveAll(s)
				}
			}
			if err := extracZip(f, internalAssetsPath); err != nil {
				return fmt.Errorf("extract yacd zip: %v", err)
			}
			m, err := filepath.Glob(internalAssetsPath + "/Yacd-*")
			if err != nil {
				return fmt.Errorf("glob Yacd: %v", err)
			}
			if len(m) != 1 {
				return fmt.Errorf("glob Yacd found %d result, expect 1", len(m))
			}
			if err := os.Rename(m[0], dstName); err != nil {
				return fmt.Errorf("rename Yacd: %v", err)
			}
		} else {
			return fmt.Errorf("open yacd.zip: %v", err)
		}
	} else {
		return fmt.Errorf("open %s.xz: %v", name, err)
	}
	if extractErr != nil {
		return extractErr
	}

	return writeFileAtomic(dir+version, []byte(assetVersion))
}

func extractAsset(i asset.File, path string) error {
	defer i.Close()
	o, err := os.Create(path)
	if err != nil {
		return err
	}
	defer o.Close()
	_, err = io.Copy(o, i)
	if err == nil {
		log.Println("Extract >>", path)
	}
	return err
}
