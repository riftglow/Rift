package libcore

const (
	// Bundled local rule-sets (2026-10-31 migration). These are the .srs
	// files shipped in the APK under assets/sing-box/ and extracted to the
	// external assets directory on first install / upgrade. Routing uses
	// these files directly; the old geoip.db / geosite.db and the db->.srs
	// conversion shim are gone.

	yacdDstFolder = "yacd"
	yacdVersion   = "yacd.version.txt"
)

// bundledSRS lists every .srs file shipped in the APK, in the order the
// routing-assets page shows them. Each has a sibling "<name>.version.txt"
// in the APK (and, once extracted, in the external assets directory) whose
// content is "<YYYY-MM-DD> <short-sha>".
var bundledSRS = []string{
	"geoip-cn.srs",
	"geosite-cn.srs",
	"geosite-category-ads-all.srs",
}

var apkAssetPrefixSingBox = "sing-box/"
var internalAssetsPath string
var externalAssetsPath string
