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

// extractSRSAssets extracts the bundled .srs rule-sets to the external
// assets directory. Unlike the old geoip/geosite db files, .srs files are
// stored raw in the APK (no xz step) and are versioned per file by
// "<YYYY-MM-DD> <short-sha>".
//
// Upgrade rule (mirrors the old db logic, but on dates, not release tags):
//   - file missing, or its version file missing -> extract;
//   - local version "Custom" (user-imported file) -> never overwrite;
//   - non-official rule-assets provider -> only extract when missing,
//     the provider's own files win;
//   - official provider -> extract only when the bundled date is newer
//     than the local date, so a newer manual update is never downgraded
//     (same-day manual content wins on a tie).
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

	// A local rule file already exists: Rift's own downloaded rules
	// always win over the bundled ones, so keep it. Only a missing
	// local file (or a missing version file) falls through to
	// extraction. A damaged local file is handled by
	// checkLocalRuleSets via backup/loud failure, not here.
	if _, err := os.Stat(dstName); err == nil {
		if _, err := os.ReadFile(externalAssetsPath + versionName); err == nil {
			return nil
		}
		// Missing version file: fall through and extract.
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
