package light.rift.fmt.wireguard

// WireGuard was removed from the sing-box 1.15 core, so there is no
// WireGuard outbound builder any more: buildSingBoxOutboundWireguardBean
// (and its genReserved -> Util.b64EncodeOneLine chain) were deleted
// 2026-11-07. What stays, deliberately: WireGuardBean, the settings
// activity, the preferences XML and the wireguard_* strings — the
// read/delete paths for old configurations still need them, and the
// import filter, the start-up guard and the edit guards still
// reference the bean.
