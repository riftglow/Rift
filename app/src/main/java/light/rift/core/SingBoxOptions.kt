package light.rift.core

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonSerializationContext
import com.google.gson.JsonSerializer
import com.google.gson.ToNumberPolicy
import com.google.gson.TypeAdapter
import com.google.gson.TypeAdapterFactory
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import java.lang.reflect.Type
import light.rift.core.utils.Util

class SingBoxOptions {

    // base

    companion object {
        private val gsonSingbox: Gson = GsonBuilder()
            .registerTypeHierarchyAdapter(SingBoxOption::class.java, SingBoxOptionSerializer())
            .setPrettyPrinting()
            .setNumberToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE)
            .setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE)
            .setLenient()
            .disableHtmlEscaping()
            .create()
    }

    open class SingBoxOption {

        @Transient
        var _hack_config_map: MutableMap<String, Any?> = HashMap()

        @Transient
        var _hack_custom_config: String? = null

        fun asMap(): Map<String, Any?> {
            @Suppress("UNCHECKED_CAST")
            return gsonSingbox.fromJson(gsonSingbox.toJson(this), Map::class.java) as Map<String, Any?>
        }
    }

    class CustomSingBoxOption(@Transient var config: String?) : SingBoxOption() {

        @Suppress("UNCHECKED_CAST")
        fun getBasicMap(): MutableMap<String, Any?> {
            val parsed = gsonSingbox.fromJson(config, Map::class.java) as? MutableMap<String, Any?>
            return parsed ?: HashMap()
        }
    }

    // 自定义序列化器
    class SingBoxOptionSerializer : JsonSerializer<SingBoxOption> {
        override fun serialize(src: SingBoxOption, typeOfSrc: Type, context: JsonSerializationContext): JsonElement {
            // 拿到原始的 delegate（默认序列化器）
            val delegate = gsonSingbox.getDelegateAdapter(
                object : TypeAdapterFactory {
                    override fun <T> create(gson: Gson, type: TypeToken<T>): TypeAdapter<T>? {
                        return null // 返回 null，表示只作为“跳过当前自定义”的 marker
                    }
                },
                TypeToken.get(src.javaClass)
            )
            val map: MutableMap<String, Any?>
            if (src is CustomSingBoxOption) {
                map = src.getBasicMap()
            } else {
                @Suppress("UNCHECKED_CAST")
                map = gsonSingbox.fromJson(delegate.toJson(src), Map::class.java) as MutableMap<String, Any?>
            }
            if (src._hack_config_map.isNotEmpty()) {
                Util.mergeMap(map, src._hack_config_map)
            }
            val customConfig = src._hack_custom_config
            if (!customConfig.isNullOrBlank()) {
                Util.mergeJSON(map, customConfig)
            }
            return gsonSingbox.toJsonTree(map)
        }
    }

    // custom classes

    class User {
        var username: String? = null
        var password: String? = null
    }

    class MyOptions : SingBoxOption() {
        var log: LogOptions? = null

        var dns: DNSOptions? = null

        var ntp: NTPOptions? = null

        var inbounds: MutableList<Inbound>? = null

        var outbounds: MutableList<SingBoxOption>? = null

        var route: RouteOptions? = null

        var experimental: ExperimentalOptions? = null

    }

    // paste generate output here

    class ClashAPIOptions : SingBoxOption() {

        var external_controller: String? = null

        var external_ui: String? = null

        var external_ui_download_url: String? = null

        var external_ui_download_detour: String? = null

        var secret: String? = null

        var default_mode: String? = null

        // Generate note: option type:  public List<String> ModeList;

    }

    class SelectorOutboundOptions : SingBoxOption() {

        var outbounds: MutableList<String>? = null

        @SerializedName("default")
        var default_: String? = null

    }

    class URLTestOutboundOptions : SingBoxOption() {

        var outbounds: MutableList<String>? = null

        var url: String? = null

        var interval: Long? = null

        var tolerance: Int? = null

    }


    class Options : SingBoxOption() {

        var `$schema`: String? = null

        var log: LogOptions? = null

        var dns: DNSOptions? = null

        var ntp: NTPOptions? = null

        var inbounds: MutableList<Inbound>? = null

        var outbounds: MutableList<Outbound>? = null

        var route: RouteOptions? = null

        var experimental: ExperimentalOptions? = null

    }

    class LogOptions : SingBoxOption() {

        var disabled: Boolean? = null

        var level: String? = null

        var output: String? = null

        var timestamp: Boolean? = null

        // Generate note: option type:  public Boolean DisableColor;

    }

    class DebugOptions : SingBoxOption() {

        var listen: String? = null

        var gc_percent: Int? = null

        var max_stack: Int? = null

        var max_threads: Int? = null

        var panic_on_fault: Boolean? = null

        var trace_back: String? = null

        var memory_limit: Long? = null

        var oom_killer: Boolean? = null

    }


    class DirectInboundOptions : SingBoxOption() {

        // Generate note: nested type ListenOptions
        var listen: String? = null

        var listen_port: Int? = null

        var tcp_fast_open: Boolean? = null

        var tcp_multi_path: Boolean? = null

        var udp_fragment: Boolean? = null

        // Generate note: option type:  public Boolean UDPFragmentDefault;

        var udp_timeout: Long? = null

        var proxy_protocol: Boolean? = null

        var proxy_protocol_accept_no_header: Boolean? = null

        var detour: String? = null

        // Generate note: nested type InboundOptions
        var sniff: Boolean? = null

        var sniff_override_destination: Boolean? = null

        var sniff_timeout: Long? = null

        var domain_strategy: String? = null

        // End of public InboundOptions ;

        // End of public ListenOptions ;

        var network: String? = null

        var override_address: String? = null

        var override_port: Int? = null

    }

    class DirectOutboundOptions : SingBoxOption() {

        // Generate note: nested type DialerOptions
        var detour: String? = null

        var bind_interface: String? = null

        var inet4_bind_address: String? = null

        var inet6_bind_address: String? = null

        var protect_path: String? = null

        var routing_mark: Int? = null

        var reuse_addr: Boolean? = null

        var connect_timeout: Long? = null

        var tcp_fast_open: Boolean? = null

        var tcp_multi_path: Boolean? = null

        var udp_fragment: Boolean? = null

        // Generate note: option type:  public Boolean UDPFragmentDefault;

        var domain_strategy: String? = null

        var fallback_delay: Long? = null

        // End of public DialerOptions ;

        var override_address: String? = null

        var override_port: Int? = null

        var proxy_protocol: Int? = null

    }

    class DNSOptions : SingBoxOption() {

        var servers: MutableList<DNSServerOptions>? = null

        var rules: MutableList<DNSRule>? = null

        @SerializedName("final")
        var final_: String? = null

        var reverse_mapping: Boolean? = null

        // sing-box 1.14: fakeip is a server in dns.servers, top-level fakeip is deprecated/removed.
        // public DNSFakeIPOptions fakeip;

        // Generate note: nested type DNSClientOptions
        var strategy: String? = null

        var disable_cache: Boolean? = null

        var disable_expire: Boolean? = null

        var independent_cache: Boolean? = null

        // Optimistic DNS（2026-11-10，规划第 14 项）：返回过期缓存并后台刷新。
        var optimistic: OptimisticDNSOptions? = null

        // End of public DNSClientOptions ;

    }

    class OptimisticDNSOptions : SingBoxOption() {

        var enabled: Boolean? = null

        var timeout: String? = null

    }

    class DNSServerOptions : SingBoxOption() {

        var type: String? = null

        var tag: String? = null

        var server: String? = null

        var server_port: Int? = null

        var path: String? = null

        var detour: String? = null

        var domain_resolver: String? = null

        var domain_strategy: String? = null

        var inet4_range: String? = null

        var inet6_range: String? = null

    }

    class DNSClientOptions : SingBoxOption() {

        var strategy: String? = null

        var disable_cache: Boolean? = null

        var disable_expire: Boolean? = null

        var independent_cache: Boolean? = null

    }

    class DNSFakeIPOptions : SingBoxOption() {

        var enabled: Boolean? = null

        var inet4_range: String? = null

        var inet6_range: String? = null

    }

    class ExperimentalOptions : SingBoxOption() {

        var clash_api: ClashAPIOptions? = null

        var v2ray_api: V2RayAPIOptions? = null

        var cache_file: CacheFile? = null

        var debug: DebugOptions? = null

    }

    class CacheFile : SingBoxOption() {

        var enabled: Boolean? = null

        var store_fakeip: Boolean? = null

        var store_dns: Boolean? = null

        var path: String? = null

        var cache_id: String? = null

    }

    class HysteriaInboundOptions : SingBoxOption() {

        // Generate note: nested type ListenOptions
        var listen: String? = null

        var listen_port: Int? = null

        var tcp_fast_open: Boolean? = null

        var tcp_multi_path: Boolean? = null

        var udp_fragment: Boolean? = null

        // Generate note: option type:  public Boolean UDPFragmentDefault;

        var udp_timeout: Long? = null

        var proxy_protocol: Boolean? = null

        var proxy_protocol_accept_no_header: Boolean? = null

        var detour: String? = null

        // Generate note: nested type InboundOptions
        var sniff: Boolean? = null

        var sniff_override_destination: Boolean? = null

        var sniff_timeout: Long? = null

        var domain_strategy: String? = null

        // End of public InboundOptions ;

        // End of public ListenOptions ;

        var up: String? = null

        var up_mbps: Int? = null

        var down: String? = null

        var down_mbps: Int? = null

        var obfs: String? = null

        var users: MutableList<HysteriaUser>? = null

        var recv_window_conn: Long? = null

        var recv_window_client: Long? = null

        var max_conn_client: Int? = null

        var disable_mtu_discovery: Boolean? = null

        var tls: InboundTLSOptions? = null

    }

    class HysteriaUser : SingBoxOption() {

        var name: String? = null

        // Generate note: Base64 String
        var auth: String? = null

        var auth_str: String? = null

    }

    class HysteriaOutboundOptions : SingBoxOption() {

        // Generate note: nested type DialerOptions
        var detour: String? = null

        var bind_interface: String? = null

        var inet4_bind_address: String? = null

        var inet6_bind_address: String? = null

        var protect_path: String? = null

        var routing_mark: Int? = null

        var reuse_addr: Boolean? = null

        var connect_timeout: Long? = null

        var tcp_fast_open: Boolean? = null

        var tcp_multi_path: Boolean? = null

        var udp_fragment: Boolean? = null

        // Generate note: option type:  public Boolean UDPFragmentDefault;

        var domain_strategy: String? = null

        var fallback_delay: Long? = null

        // End of public DialerOptions ;

        // Generate note: nested type ServerOptions
        var server: String? = null

        var server_port: Int? = null

        // End of public ServerOptions ;

        var up: String? = null

        var up_mbps: Int? = null

        var down: String? = null

        var down_mbps: Int? = null

        var obfs: String? = null

        // Generate note: Base64 String
        var auth: String? = null

        var auth_str: String? = null

        var recv_window_conn: Long? = null

        var recv_window: Long? = null

        var disable_mtu_discovery: Boolean? = null

        var network: String? = null

        var tls: OutboundTLSOptions? = null

        var server_ports: MutableList<String>? = null

        var hop_interval: String? = null

    }

    class Hysteria2InboundOptions : SingBoxOption() {

        // Generate note: nested type ListenOptions
        var listen: String? = null

        var listen_port: Int? = null

        var tcp_fast_open: Boolean? = null

        var tcp_multi_path: Boolean? = null

        var udp_fragment: Boolean? = null

        // Generate note: option type:  public Boolean UDPFragmentDefault;

        var udp_timeout: Long? = null

        var proxy_protocol: Boolean? = null

        var proxy_protocol_accept_no_header: Boolean? = null

        var detour: String? = null

        // Generate note: nested type InboundOptions
        var sniff: Boolean? = null

        var sniff_override_destination: Boolean? = null

        var sniff_timeout: Long? = null

        var domain_strategy: String? = null

        // End of public InboundOptions ;

        // End of public ListenOptions ;

        var up_mbps: Int? = null

        var down_mbps: Int? = null

        var obfs: Hysteria2Obfs? = null

        var users: MutableList<Hysteria2User>? = null

        var ignore_client_bandwidth: Boolean? = null

        var tls: InboundTLSOptions? = null

        var masquerade: String? = null

    }

    class Hysteria2Obfs : SingBoxOption() {

        var type: String? = null

        var password: String? = null

    }

    class Hysteria2User : SingBoxOption() {

        var name: String? = null

        var password: String? = null

    }

    class Hysteria2OutboundOptions : SingBoxOption() {

        // Generate note: nested type DialerOptions
        var detour: String? = null

        var bind_interface: String? = null

        var inet4_bind_address: String? = null

        var inet6_bind_address: String? = null

        var protect_path: String? = null

        var routing_mark: Int? = null

        var reuse_addr: Boolean? = null

        var connect_timeout: Long? = null

        var tcp_fast_open: Boolean? = null

        var tcp_multi_path: Boolean? = null

        var udp_fragment: Boolean? = null

        // Generate note: option type:  public Boolean UDPFragmentDefault;

        var domain_strategy: String? = null

        var fallback_delay: Long? = null

        // End of public DialerOptions ;

        // Generate note: nested type ServerOptions
        var server: String? = null

        var server_port: Int? = null

        // End of public ServerOptions ;

        var up_mbps: Int? = null

        var down_mbps: Int? = null

        var obfs: Hysteria2Obfs? = null

        var password: String? = null

        var network: String? = null

        var tls: OutboundTLSOptions? = null

        var server_ports: MutableList<String>? = null

        var hop_interval: String? = null

    }


    open class Inbound : SingBoxOption() {

        var type: String? = null

        var tag: String? = null

        // Generate note: option type:  public TunInboundOptions TunOptions;

        // Generate note: option type:  public RedirectInboundOptions RedirectOptions;

        // Generate note: option type:  public TProxyInboundOptions TProxyOptions;

        // Generate note: option type:  public DirectInboundOptions DirectOptions;

        // Generate note: option type:  public SocksInboundOptions SocksOptions;

        // Generate note: option type:  public HTTPMixedInboundOptions HTTPOptions;

        // Generate note: option type:  public HTTPMixedInboundOptions MixedOptions;

        // Generate note: option type:  public ShadowsocksInboundOptions ShadowsocksOptions;

        // Generate note: option type:  public VMessInboundOptions VMessOptions;

        // Generate note: option type:  public TrojanInboundOptions TrojanOptions;

        // Generate note: option type:  public NaiveInboundOptions NaiveOptions;

        // Generate note: option type:  public HysteriaInboundOptions HysteriaOptions;

        // Generate note: option type:  public ShadowTLSInboundOptions ShadowTLSOptions;

        // Generate note: option type:  public VLESSInboundOptions VLESSOptions;

        // Generate note: option type:  public TUICInboundOptions TUICOptions;

        // Generate note: option type:  public Hysteria2InboundOptions Hysteria2Options;

    }

    class InboundOptions : SingBoxOption() {

        var sniff: Boolean? = null

        var sniff_override_destination: Boolean? = null

        var sniff_timeout: Long? = null

        var domain_strategy: String? = null

    }

    class ListenOptions : SingBoxOption() {

        var listen: String? = null

        var listen_port: Int? = null

        var tcp_fast_open: Boolean? = null

        var tcp_multi_path: Boolean? = null

        var udp_fragment: Boolean? = null

        // Generate note: option type:  public Boolean UDPFragmentDefault;

        var udp_timeout: Long? = null

        var proxy_protocol: Boolean? = null

        var proxy_protocol_accept_no_header: Boolean? = null

        var detour: String? = null

        // Generate note: nested type InboundOptions
        var sniff: Boolean? = null

        var sniff_override_destination: Boolean? = null

        var sniff_timeout: Long? = null

        var domain_strategy: String? = null

        // End of public InboundOptions ;

    }

    class NaiveInboundOptions : SingBoxOption() {

        // Generate note: nested type ListenOptions
        var listen: String? = null

        var listen_port: Int? = null

        var tcp_fast_open: Boolean? = null

        var tcp_multi_path: Boolean? = null

        var udp_fragment: Boolean? = null

        // Generate note: option type:  public Boolean UDPFragmentDefault;

        var udp_timeout: Long? = null

        var proxy_protocol: Boolean? = null

        var proxy_protocol_accept_no_header: Boolean? = null

        var detour: String? = null

        // Generate note: nested type InboundOptions
        var sniff: Boolean? = null

        var sniff_override_destination: Boolean? = null

        var sniff_timeout: Long? = null

        var domain_strategy: String? = null

        // End of public InboundOptions ;

        // End of public ListenOptions ;

        var users: MutableList<User>? = null

        var network: String? = null

        var tls: InboundTLSOptions? = null

    }

    class NTPOptions : SingBoxOption() {

        var enabled: Boolean? = null

        var interval: Long? = null

        var write_to_system: Boolean? = null

        // Generate note: nested type ServerOptions
        var server: String? = null

        var server_port: Int? = null

        // End of public ServerOptions ;

        // Generate note: nested type DialerOptions
        var detour: String? = null

        var bind_interface: String? = null

        var inet4_bind_address: String? = null

        var inet6_bind_address: String? = null

        var protect_path: String? = null

        var routing_mark: Int? = null

        var reuse_addr: Boolean? = null

        var connect_timeout: Long? = null

        var tcp_fast_open: Boolean? = null

        var tcp_multi_path: Boolean? = null

        var udp_fragment: Boolean? = null

        // Generate note: option type:  public Boolean UDPFragmentDefault;

        var domain_strategy: String? = null

        var fallback_delay: Long? = null

        // End of public DialerOptions ;

    }


    open class Outbound : SingBoxOption() {

        var type: String? = null

        var tag: String? = null

        // Generate note: option type:  public DirectOutboundOptions DirectOptions;

        // Generate note: option type:  public SocksOutboundOptions SocksOptions;

        // Generate note: option type:  public HTTPOutboundOptions HTTPOptions;

        // Generate note: option type:  public ShadowsocksOutboundOptions ShadowsocksOptions;

        // Generate note: option type:  public VMessOutboundOptions VMessOptions;

        // Generate note: option type:  public TrojanOutboundOptions TrojanOptions;

        // Generate note: option type:  public WireGuardOutboundOptions WireGuardOptions;

        // Generate note: option type:  public HysteriaOutboundOptions HysteriaOptions;

        // Generate note: option type:  public TorOutboundOptions TorOptions;

        // Generate note: option type:  public SSHOutboundOptions SSHOptions;

        // Generate note: option type:  public ShadowTLSOutboundOptions ShadowTLSOptions;

        // Generate note: option type:  public ShadowsocksROutboundOptions ShadowsocksROptions;

        // Generate note: option type:  public VLESSOutboundOptions VLESSOptions;

        // Generate note: option type:  public TUICOutboundOptions TUICOptions;

        // Generate note: option type:  public Hysteria2OutboundOptions Hysteria2Options;

        // Generate note: option type:  public SelectorOutboundOptions SelectorOptions;

        // Generate note: option type:  public URLTestOutboundOptions URLTestOptions;

    }

    class DialerOptions : SingBoxOption() {

        var detour: String? = null

        var bind_interface: String? = null

        var inet4_bind_address: String? = null

        var inet6_bind_address: String? = null

        var protect_path: String? = null

        var routing_mark: Int? = null

        var reuse_addr: Boolean? = null

        var connect_timeout: Long? = null

        var tcp_fast_open: Boolean? = null

        var tcp_multi_path: Boolean? = null

        var udp_fragment: Boolean? = null

        // Generate note: option type:  public Boolean UDPFragmentDefault;

        var domain_strategy: String? = null

        var fallback_delay: Long? = null

    }

    class ServerOptions : SingBoxOption() {

        var server: String? = null

        var server_port: Int? = null

    }

    class MultiplexOptions : SingBoxOption() {

        var enabled: Boolean? = null

        var protocol: String? = null

        var max_connections: Int? = null

        var min_streams: Int? = null

        var max_streams: Int? = null

        var padding: Boolean? = null

    }

    class OnDemandOptions : SingBoxOption() {

        var enabled: Boolean? = null

        var rules: MutableList<OnDemandRule>? = null

    }

    class OnDemandRule : SingBoxOption() {

        var action: String? = null

        // Generate note: Listable
        var dns_search_domain_match: MutableList<String>? = null

        // Generate note: Listable
        var dns_server_address_match: MutableList<String>? = null

        var interface_type_match: String? = null

        // Generate note: Listable
        var ssid_match: MutableList<String>? = null

        var probe_url: String? = null

    }


    class RedirectInboundOptions : SingBoxOption() {

        // Generate note: nested type ListenOptions
        var listen: String? = null

        var listen_port: Int? = null

        var tcp_fast_open: Boolean? = null

        var tcp_multi_path: Boolean? = null

        var udp_fragment: Boolean? = null

        // Generate note: option type:  public Boolean UDPFragmentDefault;

        var udp_timeout: Long? = null

        var proxy_protocol: Boolean? = null

        var proxy_protocol_accept_no_header: Boolean? = null

        var detour: String? = null

        // Generate note: nested type InboundOptions
        var sniff: Boolean? = null

        var sniff_override_destination: Boolean? = null

        var sniff_timeout: Long? = null

        var domain_strategy: String? = null

        // End of public InboundOptions ;

        // End of public ListenOptions ;

    }

    class TProxyInboundOptions : SingBoxOption() {

        // Generate note: nested type ListenOptions
        var listen: String? = null

        var listen_port: Int? = null

        var tcp_fast_open: Boolean? = null

        var tcp_multi_path: Boolean? = null

        var udp_fragment: Boolean? = null

        // Generate note: option type:  public Boolean UDPFragmentDefault;

        var udp_timeout: Long? = null

        var proxy_protocol: Boolean? = null

        var proxy_protocol_accept_no_header: Boolean? = null

        var detour: String? = null

        // Generate note: nested type InboundOptions
        var sniff: Boolean? = null

        var sniff_override_destination: Boolean? = null

        var sniff_timeout: Long? = null

        var domain_strategy: String? = null

        // End of public InboundOptions ;

        // End of public ListenOptions ;

        var network: String? = null

    }

    class RouteOptions : SingBoxOption() {

        var rules: MutableList<Rule>? = null

        var rule_set: MutableList<RuleSet>? = null

        @SerializedName("final")
        var final_: String? = null

        var find_process: Boolean? = null

        var auto_detect_interface: Boolean? = null

        var override_android_vpn: Boolean? = null

        var default_interface: String? = null

        var default_mark: Int? = null

    }


    open class Rule : SingBoxOption() {

        var type: String? = null

        // Generate note: option type:  public DefaultRule DefaultOptions;

        // Generate note: option type:  public LogicalRule LogicalOptions;

    }

    class RuleSet : SingBoxOption() {

        var type: String? = null

        var tag: String? = null

        var format: String? = null

        var path: String? = null

        var url: String? = null

    }

    class DefaultRule : SingBoxOption() {

        // Generate note: Listable
        var inbound: MutableList<String>? = null

        var ip_version: Int? = null

        // Generate note: Listable
        var network: MutableList<String>? = null

        // Generate note: Listable
        var auth_user: MutableList<String>? = null

        // Generate note: Listable
        var protocol: MutableList<String>? = null

        // Generate note: Listable
        var domain: MutableList<String>? = null

        // Generate note: Listable
        var domain_suffix: MutableList<String>? = null

        // Generate note: Listable
        var domain_keyword: MutableList<String>? = null

        // Generate note: Listable
        var domain_regex: MutableList<String>? = null

        // Generate note: Listable
        var source_ip_cidr: MutableList<String>? = null

        // Generate note: Listable
        var ip_cidr: MutableList<String>? = null

        // Generate note: Listable
        var source_port: MutableList<Int>? = null

        // Generate note: Listable
        var source_port_range: MutableList<String>? = null

        // Generate note: Listable
        var port: MutableList<Int>? = null

        // Generate note: Listable
        var port_range: MutableList<String>? = null

        // Generate note: Listable
        var process_name: MutableList<String>? = null

        // Generate note: Listable
        var process_path: MutableList<String>? = null

        // Generate note: Listable
        var package_name: MutableList<String>? = null

        // Generate note: Listable
        var user: MutableList<String>? = null

        // Generate note: Listable
        var user_id: MutableList<Int>? = null

        var clash_mode: String? = null

        var invert: Boolean? = null

        var outbound: String? = null

    }


    open class DNSRule : SingBoxOption() {

        var type: String? = null

        // Generate note: option type:  public DefaultDNSRule DefaultOptions;

        // Generate note: option type:  public LogicalDNSRule LogicalOptions;

    }

    class DefaultDNSRule : SingBoxOption() {

        // Generate note: Listable
        var inbound: MutableList<String>? = null

        var ip_version: Int? = null

        // Generate note: Listable
        var query_type: MutableList<String>? = null

        // Generate note: Listable
        var network: MutableList<String>? = null

        // Generate note: Listable
        var auth_user: MutableList<String>? = null

        // Generate note: Listable
        var protocol: MutableList<String>? = null

        // Generate note: Listable
        var domain: MutableList<String>? = null

        // Generate note: Listable
        var domain_suffix: MutableList<String>? = null

        // Generate note: Listable
        var domain_keyword: MutableList<String>? = null

        // Generate note: Listable
        var domain_regex: MutableList<String>? = null

        // Generate note: Listable
        var geosite: MutableList<String>? = null

        // Generate note: Listable
        var source_ip_cidr: MutableList<String>? = null

        // Generate note: Listable
        var source_port: MutableList<Int>? = null

        // Generate note: Listable
        var source_port_range: MutableList<String>? = null

        // Generate note: Listable
        var port: MutableList<Int>? = null

        // Generate note: Listable
        var port_range: MutableList<String>? = null

        // Generate note: Listable
        var process_name: MutableList<String>? = null

        // Generate note: Listable
        var process_path: MutableList<String>? = null

        // Generate note: Listable
        var package_name: MutableList<String>? = null

        // Generate note: Listable
        var user: MutableList<String>? = null

        // Generate note: Listable
        var user_id: MutableList<Int>? = null

        // Generate note: Listable
        var outbound: MutableList<String>? = null

        var clash_mode: String? = null

        var invert: Boolean? = null

        var server: String? = null

        var disable_cache: Boolean? = null

        var rewrite_ttl: Int? = null

    }

    class ShadowsocksInboundOptions : SingBoxOption() {

        // Generate note: nested type ListenOptions
        var listen: String? = null

        var listen_port: Int? = null

        var tcp_fast_open: Boolean? = null

        var tcp_multi_path: Boolean? = null

        var udp_fragment: Boolean? = null

        // Generate note: option type:  public Boolean UDPFragmentDefault;

        var udp_timeout: Long? = null

        var proxy_protocol: Boolean? = null

        var proxy_protocol_accept_no_header: Boolean? = null

        var detour: String? = null

        // Generate note: nested type InboundOptions
        var sniff: Boolean? = null

        var sniff_override_destination: Boolean? = null

        var sniff_timeout: Long? = null

        var domain_strategy: String? = null

        // End of public InboundOptions ;

        // End of public ListenOptions ;

        var network: String? = null

        var method: String? = null

        var password: String? = null

        var users: MutableList<ShadowsocksUser>? = null

        var destinations: MutableList<ShadowsocksDestination>? = null

    }

    class ShadowsocksUser : SingBoxOption() {

        var name: String? = null

        var password: String? = null

    }

    class ShadowsocksDestination : SingBoxOption() {

        var name: String? = null

        var password: String? = null

        // Generate note: nested type ServerOptions
        var server: String? = null

        var server_port: Int? = null

        // End of public ServerOptions ;

    }

    class ShadowsocksOutboundOptions : SingBoxOption() {

        // Generate note: nested type DialerOptions
        var detour: String? = null

        var bind_interface: String? = null

        var inet4_bind_address: String? = null

        var inet6_bind_address: String? = null

        var protect_path: String? = null

        var routing_mark: Int? = null

        var reuse_addr: Boolean? = null

        var connect_timeout: Long? = null

        var tcp_fast_open: Boolean? = null

        var tcp_multi_path: Boolean? = null

        var udp_fragment: Boolean? = null

        // Generate note: option type:  public Boolean UDPFragmentDefault;

        var domain_strategy: String? = null

        var fallback_delay: Long? = null

        // End of public DialerOptions ;

        // Generate note: nested type ServerOptions
        var server: String? = null

        var server_port: Int? = null

        // End of public ServerOptions ;

        var method: String? = null

        var password: String? = null

        var plugin: String? = null

        var plugin_opts: String? = null

        var network: String? = null

        var udp_over_tcp: UDPOverTCPOptions? = null

        var multiplex: MultiplexOptions? = null

    }

    class ShadowsocksROutboundOptions : SingBoxOption() {

        // Generate note: nested type DialerOptions
        var detour: String? = null

        var bind_interface: String? = null

        var inet4_bind_address: String? = null

        var inet6_bind_address: String? = null

        var protect_path: String? = null

        var routing_mark: Int? = null

        var reuse_addr: Boolean? = null

        var connect_timeout: Long? = null

        var tcp_fast_open: Boolean? = null

        var tcp_multi_path: Boolean? = null

        var udp_fragment: Boolean? = null

        // Generate note: option type:  public Boolean UDPFragmentDefault;

        var domain_strategy: String? = null

        var fallback_delay: Long? = null

        // End of public DialerOptions ;

        // Generate note: nested type ServerOptions
        var server: String? = null

        var server_port: Int? = null

        // End of public ServerOptions ;

        var method: String? = null

        var password: String? = null

        var obfs: String? = null

        var obfs_param: String? = null

        var protocol: String? = null

        var protocol_param: String? = null

        var network: String? = null

    }

    class ShadowTLSInboundOptions : SingBoxOption() {

        // Generate note: nested type ListenOptions
        var listen: String? = null

        var listen_port: Int? = null

        var tcp_fast_open: Boolean? = null

        var tcp_multi_path: Boolean? = null

        var udp_fragment: Boolean? = null

        // Generate note: option type:  public Boolean UDPFragmentDefault;

        var udp_timeout: Long? = null

        var proxy_protocol: Boolean? = null

        var proxy_protocol_accept_no_header: Boolean? = null

        var detour: String? = null

        // Generate note: nested type InboundOptions
        var sniff: Boolean? = null

        var sniff_override_destination: Boolean? = null

        var sniff_timeout: Long? = null

        var domain_strategy: String? = null

        // End of public InboundOptions ;

        // End of public ListenOptions ;

        var version: Int? = null

        var password: String? = null

        var users: MutableList<ShadowTLSUser>? = null

        var handshake: ShadowTLSHandshakeOptions? = null

        var handshake_for_server_name: MutableMap<String, ShadowTLSHandshakeOptions>? = null

        var strict_mode: Boolean? = null

    }

    class ShadowTLSUser : SingBoxOption() {

        var name: String? = null

        var password: String? = null

    }

    class ShadowTLSHandshakeOptions : SingBoxOption() {

        // Generate note: nested type ServerOptions
        var server: String? = null

        var server_port: Int? = null

        // End of public ServerOptions ;

        // Generate note: nested type DialerOptions
        var detour: String? = null

        var bind_interface: String? = null

        var inet4_bind_address: String? = null

        var inet6_bind_address: String? = null

        var protect_path: String? = null

        var routing_mark: Int? = null

        var reuse_addr: Boolean? = null

        var connect_timeout: Long? = null

        var tcp_fast_open: Boolean? = null

        var tcp_multi_path: Boolean? = null

        var udp_fragment: Boolean? = null

        // Generate note: option type:  public Boolean UDPFragmentDefault;

        var domain_strategy: String? = null

        var fallback_delay: Long? = null

        // End of public DialerOptions ;

    }

    class ShadowTLSOutboundOptions : SingBoxOption() {

        // Generate note: nested type DialerOptions
        var detour: String? = null

        var bind_interface: String? = null

        var inet4_bind_address: String? = null

        var inet6_bind_address: String? = null

        var protect_path: String? = null

        var routing_mark: Int? = null

        var reuse_addr: Boolean? = null

        var connect_timeout: Long? = null

        var tcp_fast_open: Boolean? = null

        var tcp_multi_path: Boolean? = null

        var udp_fragment: Boolean? = null

        // Generate note: option type:  public Boolean UDPFragmentDefault;

        var domain_strategy: String? = null

        var fallback_delay: Long? = null

        // End of public DialerOptions ;

        // Generate note: nested type ServerOptions
        var server: String? = null

        var server_port: Int? = null

        // End of public ServerOptions ;

        var version: Int? = null

        var password: String? = null

        var tls: OutboundTLSOptions? = null

    }

    class SocksInboundOptions : SingBoxOption() {

        // Generate note: nested type ListenOptions
        var listen: String? = null

        var listen_port: Int? = null

        var tcp_fast_open: Boolean? = null

        var tcp_multi_path: Boolean? = null

        var udp_fragment: Boolean? = null

        // Generate note: option type:  public Boolean UDPFragmentDefault;

        var udp_timeout: Long? = null

        var proxy_protocol: Boolean? = null

        var proxy_protocol_accept_no_header: Boolean? = null

        var detour: String? = null

        // Generate note: nested type InboundOptions
        var sniff: Boolean? = null

        var sniff_override_destination: Boolean? = null

        var sniff_timeout: Long? = null

        var domain_strategy: String? = null

        // End of public InboundOptions ;

        // End of public ListenOptions ;

        var users: MutableList<User>? = null

    }

    class HTTPMixedInboundOptions : SingBoxOption() {

        // Generate note: nested type ListenOptions
        var listen: String? = null

        var listen_port: Int? = null

        var tcp_fast_open: Boolean? = null

        var tcp_multi_path: Boolean? = null

        var udp_fragment: Boolean? = null

        // Generate note: option type:  public Boolean UDPFragmentDefault;

        var udp_timeout: Long? = null

        var proxy_protocol: Boolean? = null

        var proxy_protocol_accept_no_header: Boolean? = null

        var detour: String? = null

        // Generate note: nested type InboundOptions
        var sniff: Boolean? = null

        var sniff_override_destination: Boolean? = null

        var sniff_timeout: Long? = null

        var domain_strategy: String? = null

        // End of public InboundOptions ;

        // End of public ListenOptions ;

        var users: MutableList<User>? = null

        var set_system_proxy: Boolean? = null

        var tls: InboundTLSOptions? = null

    }

    class SocksOutboundOptions : SingBoxOption() {

        // Generate note: nested type DialerOptions
        var detour: String? = null

        var bind_interface: String? = null

        var inet4_bind_address: String? = null

        var inet6_bind_address: String? = null

        var protect_path: String? = null

        var routing_mark: Int? = null

        var reuse_addr: Boolean? = null

        var connect_timeout: Long? = null

        var tcp_fast_open: Boolean? = null

        var tcp_multi_path: Boolean? = null

        var udp_fragment: Boolean? = null

        // Generate note: option type:  public Boolean UDPFragmentDefault;

        var domain_strategy: String? = null

        var fallback_delay: Long? = null

        // End of public DialerOptions ;

        // Generate note: nested type ServerOptions
        var server: String? = null

        var server_port: Int? = null

        // End of public ServerOptions ;

        var version: String? = null

        var username: String? = null

        var password: String? = null

        var network: String? = null

        var udp_over_tcp: UDPOverTCPOptions? = null

    }

    class HTTPOutboundOptions : SingBoxOption() {

        // Generate note: nested type DialerOptions
        var detour: String? = null

        var bind_interface: String? = null

        var inet4_bind_address: String? = null

        var inet6_bind_address: String? = null

        var protect_path: String? = null

        var routing_mark: Int? = null

        var reuse_addr: Boolean? = null

        var connect_timeout: Long? = null

        var tcp_fast_open: Boolean? = null

        var tcp_multi_path: Boolean? = null

        var udp_fragment: Boolean? = null

        // Generate note: option type:  public Boolean UDPFragmentDefault;

        var domain_strategy: String? = null

        var fallback_delay: Long? = null

        // End of public DialerOptions ;

        // Generate note: nested type ServerOptions
        var server: String? = null

        var server_port: Int? = null

        // End of public ServerOptions ;

        var username: String? = null

        var password: String? = null

        var tls: OutboundTLSOptions? = null

        var path: String? = null

        var headers: MutableMap<String, String>? = null

    }

    class SSHOutboundOptions : SingBoxOption() {

        // Generate note: nested type DialerOptions
        var detour: String? = null

        var bind_interface: String? = null

        var inet4_bind_address: String? = null

        var inet6_bind_address: String? = null

        var protect_path: String? = null

        var routing_mark: Int? = null

        var reuse_addr: Boolean? = null

        var connect_timeout: Long? = null

        var tcp_fast_open: Boolean? = null

        var tcp_multi_path: Boolean? = null

        var udp_fragment: Boolean? = null

        // Generate note: option type:  public Boolean UDPFragmentDefault;

        var domain_strategy: String? = null

        var fallback_delay: Long? = null

        // End of public DialerOptions ;

        // Generate note: nested type ServerOptions
        var server: String? = null

        var server_port: Int? = null

        // End of public ServerOptions ;

        var user: String? = null

        var password: String? = null

        var private_key: String? = null

        var private_key_path: String? = null

        var private_key_passphrase: String? = null

        // Generate note: Listable
        var host_key: MutableList<String>? = null

        // Generate note: Listable
        var host_key_algorithms: MutableList<String>? = null

        var client_version: String? = null

    }

    class InboundTLSOptions : SingBoxOption() {

        var enabled: Boolean? = null

        var server_name: String? = null

        var insecure: Boolean? = null

        // Generate note: Listable
        var alpn: MutableList<String>? = null

        var min_version: String? = null

        var max_version: String? = null

        // Generate note: Listable
        var cipher_suites: MutableList<String>? = null

        // Generate note: Listable
        var certificate: MutableList<String>? = null

        var certificate_path: String? = null

        // Generate note: Listable
        var key: MutableList<String>? = null

        var key_path: String? = null

        var acme: InboundACMEOptions? = null

        var ech: InboundECHOptions? = null

        var reality: InboundRealityOptions? = null

    }

    class OutboundTLSOptions : SingBoxOption() {

        var enabled: Boolean? = null

        var disable_sni: Boolean? = null

        var server_name: String? = null

        var insecure: Boolean? = null

        // Generate note: Listable
        var alpn: MutableList<String>? = null

        var min_version: String? = null

        var max_version: String? = null

        // Generate note: Listable
        var cipher_suites: MutableList<String>? = null

        var certificate: String? = null

        var certificate_path: String? = null

        var ech: OutboundECHOptions? = null

        var utls: OutboundUTLSOptions? = null

        var reality: OutboundRealityOptions? = null

    }

    class InboundRealityOptions : SingBoxOption() {

        var enabled: Boolean? = null

        var handshake: InboundRealityHandshakeOptions? = null

        var private_key: String? = null

        // Generate note: Listable
        var short_id: MutableList<String>? = null

        var max_time_difference: Long? = null

    }

    class InboundRealityHandshakeOptions : SingBoxOption() {

        // Generate note: nested type ServerOptions
        var server: String? = null

        var server_port: Int? = null

        // End of public ServerOptions ;

        // Generate note: nested type DialerOptions
        var detour: String? = null

        var bind_interface: String? = null

        var inet4_bind_address: String? = null

        var inet6_bind_address: String? = null

        var protect_path: String? = null

        var routing_mark: Int? = null

        var reuse_addr: Boolean? = null

        var connect_timeout: Long? = null

        var tcp_fast_open: Boolean? = null

        var tcp_multi_path: Boolean? = null

        var udp_fragment: Boolean? = null

        // Generate note: option type:  public Boolean UDPFragmentDefault;

        var domain_strategy: String? = null

        var fallback_delay: Long? = null

        // End of public DialerOptions ;

    }

    class InboundECHOptions : SingBoxOption() {

        var enabled: Boolean? = null

        // Generate note: Listable
        var key: MutableList<String>? = null

        var key_path: String? = null

    }

    class OutboundECHOptions : SingBoxOption() {

        var enabled: Boolean? = null

        // Generate note: Listable
        var config: MutableList<String>? = null

        var config_path: String? = null

    }

    class OutboundUTLSOptions : SingBoxOption() {

        var enabled: Boolean? = null

        var fingerprint: String? = null

    }

    class OutboundRealityOptions : SingBoxOption() {

        var enabled: Boolean? = null

        var public_key: String? = null

        var short_id: String? = null

    }

    class InboundACMEOptions : SingBoxOption() {

        // Generate note: Listable
        var domain: MutableList<String>? = null

        var data_directory: String? = null

        var default_server_name: String? = null

        var email: String? = null

        var provider: String? = null

        var disable_http_challenge: Boolean? = null

        var disable_tls_alpn_challenge: Boolean? = null

        var alternative_http_port: Int? = null

        var alternative_tls_port: Int? = null

        var external_account: ACMEExternalAccountOptions? = null

    }

    class ACMEExternalAccountOptions : SingBoxOption() {

        var key_id: String? = null

        var mac_key: String? = null

    }

    class TorOutboundOptions : SingBoxOption() {

        // Generate note: nested type DialerOptions
        var detour: String? = null

        var bind_interface: String? = null

        var inet4_bind_address: String? = null

        var inet6_bind_address: String? = null

        var protect_path: String? = null

        var routing_mark: Int? = null

        var reuse_addr: Boolean? = null

        var connect_timeout: Long? = null

        var tcp_fast_open: Boolean? = null

        var tcp_multi_path: Boolean? = null

        var udp_fragment: Boolean? = null

        // Generate note: option type:  public Boolean UDPFragmentDefault;

        var domain_strategy: String? = null

        var fallback_delay: Long? = null

        // End of public DialerOptions ;

        var executable_path: String? = null

        var extra_args: MutableList<String>? = null

        var data_directory: String? = null

        var torrc: MutableMap<String, String>? = null

    }

    class TrojanInboundOptions : SingBoxOption() {

        // Generate note: nested type ListenOptions
        var listen: String? = null

        var listen_port: Int? = null

        var tcp_fast_open: Boolean? = null

        var tcp_multi_path: Boolean? = null

        var udp_fragment: Boolean? = null

        // Generate note: option type:  public Boolean UDPFragmentDefault;

        var udp_timeout: Long? = null

        var proxy_protocol: Boolean? = null

        var proxy_protocol_accept_no_header: Boolean? = null

        var detour: String? = null

        // Generate note: nested type InboundOptions
        var sniff: Boolean? = null

        var sniff_override_destination: Boolean? = null

        var sniff_timeout: Long? = null

        var domain_strategy: String? = null

        // End of public InboundOptions ;

        // End of public ListenOptions ;

        var users: MutableList<TrojanUser>? = null

        var tls: InboundTLSOptions? = null

        var fallback: ServerOptions? = null

        var fallback_for_alpn: MutableMap<String, ServerOptions>? = null

        var transport: V2RayTransportOptions? = null

    }

    class TrojanUser : SingBoxOption() {

        var name: String? = null

        var password: String? = null

    }

    class TrojanOutboundOptions : SingBoxOption() {

        // Generate note: nested type DialerOptions
        var detour: String? = null

        var bind_interface: String? = null

        var inet4_bind_address: String? = null

        var inet6_bind_address: String? = null

        var protect_path: String? = null

        var routing_mark: Int? = null

        var reuse_addr: Boolean? = null

        var connect_timeout: Long? = null

        var tcp_fast_open: Boolean? = null

        var tcp_multi_path: Boolean? = null

        var udp_fragment: Boolean? = null

        // Generate note: option type:  public Boolean UDPFragmentDefault;

        var domain_strategy: String? = null

        var fallback_delay: Long? = null

        // End of public DialerOptions ;

        // Generate note: nested type ServerOptions
        var server: String? = null

        var server_port: Int? = null

        // End of public ServerOptions ;

        var password: String? = null

        var network: String? = null

        var tls: OutboundTLSOptions? = null

        var multiplex: MultiplexOptions? = null

        var transport: V2RayTransportOptions? = null

    }

    class TUICInboundOptions : SingBoxOption() {

        // Generate note: nested type ListenOptions
        var listen: String? = null

        var listen_port: Int? = null

        var tcp_fast_open: Boolean? = null

        var tcp_multi_path: Boolean? = null

        var udp_fragment: Boolean? = null

        // Generate note: option type:  public Boolean UDPFragmentDefault;

        var udp_timeout: Long? = null

        var proxy_protocol: Boolean? = null

        var proxy_protocol_accept_no_header: Boolean? = null

        var detour: String? = null

        // Generate note: nested type InboundOptions
        var sniff: Boolean? = null

        var sniff_override_destination: Boolean? = null

        var sniff_timeout: Long? = null

        var domain_strategy: String? = null

        // End of public InboundOptions ;

        // End of public ListenOptions ;

        var users: MutableList<TUICUser>? = null

        var congestion_control: String? = null

        var auth_timeout: Long? = null

        var zero_rtt_handshake: Boolean? = null

        var heartbeat: Long? = null

        var tls: InboundTLSOptions? = null

    }

    class TUICUser : SingBoxOption() {

        var name: String? = null

        var uuid: String? = null

        var password: String? = null

    }

    class TUICOutboundOptions : SingBoxOption() {

        // Generate note: nested type DialerOptions
        var detour: String? = null

        var bind_interface: String? = null

        var inet4_bind_address: String? = null

        var inet6_bind_address: String? = null

        var protect_path: String? = null

        var routing_mark: Int? = null

        var reuse_addr: Boolean? = null

        var connect_timeout: Long? = null

        var tcp_fast_open: Boolean? = null

        var tcp_multi_path: Boolean? = null

        var udp_fragment: Boolean? = null

        // Generate note: option type:  public Boolean UDPFragmentDefault;

        var domain_strategy: String? = null

        var fallback_delay: Long? = null

        // End of public DialerOptions ;

        // Generate note: nested type ServerOptions
        var server: String? = null

        var server_port: Int? = null

        // End of public ServerOptions ;

        var uuid: String? = null

        var password: String? = null

        var congestion_control: String? = null

        var udp_relay_mode: String? = null

        var udp_over_stream: Boolean? = null

        var zero_rtt_handshake: Boolean? = null

        var heartbeat: Long? = null

        var network: String? = null

        var tls: OutboundTLSOptions? = null

    }

    class TunInboundOptions : SingBoxOption() {

        var interface_name: String? = null

        var mtu: Int? = null

        // Generate note: Listable
        var inet4_address: MutableList<String>? = null

        // Generate note: Listable
        var inet6_address: MutableList<String>? = null

        var auto_route: Boolean? = null

        var strict_route: Boolean? = null

        // Generate note: Listable
        var inet4_route_address: MutableList<String>? = null

        // Generate note: Listable
        var inet6_route_address: MutableList<String>? = null

        // Generate note: Listable
        var include_interface: MutableList<String>? = null

        // Generate note: Listable
        var exclude_interface: MutableList<String>? = null

        // Generate note: Listable
        var include_uid: MutableList<Int>? = null

        // Generate note: Listable
        var include_uid_range: MutableList<String>? = null

        // Generate note: Listable
        var exclude_uid: MutableList<Int>? = null

        // Generate note: Listable
        var exclude_uid_range: MutableList<String>? = null

        // Generate note: Listable
        var include_android_user: MutableList<Int>? = null

        // Generate note: Listable
        var include_package: MutableList<String>? = null

        // Generate note: Listable
        var exclude_package: MutableList<String>? = null

        var endpoint_independent_nat: Boolean? = null

        var udp_timeout: Long? = null

        var stack: String? = null

        var platform: TunPlatformOptions? = null

        // Generate note: nested type InboundOptions
        var sniff: Boolean? = null

        var sniff_override_destination: Boolean? = null

        var sniff_timeout: Long? = null

        var domain_strategy: String? = null

        // End of public InboundOptions ;

    }

    class TunPlatformOptions : SingBoxOption() {

        var http_proxy: HTTPProxyOptions? = null

    }

    class HTTPProxyOptions : SingBoxOption() {

        var enabled: Boolean? = null

        // Generate note: nested type ServerOptions
        var server: String? = null

        var server_port: Int? = null

        // End of public ServerOptions ;

    }


    class UDPOverTCPOptions : SingBoxOption() {

        var enabled: Boolean? = null

        var version: Int? = null

    }

    class V2RayAPIOptions : SingBoxOption() {

        var listen: String? = null

        var stats: V2RayStatsServiceOptions? = null

    }

    class V2RayStatsServiceOptions : SingBoxOption() {

        var enabled: Boolean? = null

        var inbounds: MutableList<String>? = null

        var outbounds: MutableList<String>? = null

        var users: MutableList<String>? = null

    }


    open class V2RayTransportOptions : SingBoxOption() {

        var type: String? = null

        // Generate note: option type:  public V2RayHTTPOptions HTTPOptions;

        // Generate note: option type:  public V2RayWebsocketOptions WebsocketOptions;

        // Generate note: option type:  public V2RayQUICOptions QUICOptions;

        // Generate note: option type:  public V2RayGRPCOptions GRPCOptions;

    }

    class V2RayHTTPOptions : SingBoxOption() {

        // Generate note: Listable
        var host: MutableList<String>? = null

        var path: String? = null

        var method: String? = null

        var headers: MutableMap<String, String>? = null

        var idle_timeout: Long? = null

        var ping_timeout: Long? = null

    }

    class V2RayWebsocketOptions : SingBoxOption() {

        var path: String? = null

        var headers: MutableMap<String, String>? = null

        var max_early_data: Int? = null

        var early_data_header_name: String? = null

    }


    class V2RayGRPCOptions : SingBoxOption() {

        var service_name: String? = null

        var idle_timeout: Long? = null

        var ping_timeout: Long? = null

        var permit_without_stream: Boolean? = null

        // Generate note: option type:  public Boolean ForceLite;

    }

    class V2RayHTTPUpgradeOptions : SingBoxOption() {

        var host: String? = null

        var path: String? = null

        var headers: MutableMap<String, String>? = null

    }

    class VLESSInboundOptions : SingBoxOption() {

        // Generate note: nested type ListenOptions
        var listen: String? = null

        var listen_port: Int? = null

        var tcp_fast_open: Boolean? = null

        var tcp_multi_path: Boolean? = null

        var udp_fragment: Boolean? = null

        // Generate note: option type:  public Boolean UDPFragmentDefault;

        var udp_timeout: Long? = null

        var proxy_protocol: Boolean? = null

        var proxy_protocol_accept_no_header: Boolean? = null

        var detour: String? = null

        // Generate note: nested type InboundOptions
        var sniff: Boolean? = null

        var sniff_override_destination: Boolean? = null

        var sniff_timeout: Long? = null

        var domain_strategy: String? = null

        // End of public InboundOptions ;

        // End of public ListenOptions ;

        var users: MutableList<VLESSUser>? = null

        var tls: InboundTLSOptions? = null

        var transport: V2RayTransportOptions? = null

    }

    class VLESSUser : SingBoxOption() {

        var name: String? = null

        var uuid: String? = null

        var flow: String? = null

    }

    class VLESSOutboundOptions : SingBoxOption() {

        // Generate note: nested type DialerOptions
        var detour: String? = null

        var bind_interface: String? = null

        var inet4_bind_address: String? = null

        var inet6_bind_address: String? = null

        var protect_path: String? = null

        var routing_mark: Int? = null

        var reuse_addr: Boolean? = null

        var connect_timeout: Long? = null

        var tcp_fast_open: Boolean? = null

        var tcp_multi_path: Boolean? = null

        var udp_fragment: Boolean? = null

        // Generate note: option type:  public Boolean UDPFragmentDefault;

        var domain_strategy: String? = null

        var fallback_delay: Long? = null

        // End of public DialerOptions ;

        // Generate note: nested type ServerOptions
        var server: String? = null

        var server_port: Int? = null

        // End of public ServerOptions ;

        var uuid: String? = null

        var flow: String? = null

        var network: String? = null

        var tls: OutboundTLSOptions? = null

        var multiplex: MultiplexOptions? = null

        var transport: V2RayTransportOptions? = null

        var packet_encoding: String? = null

    }

    class VMessInboundOptions : SingBoxOption() {

        // Generate note: nested type ListenOptions
        var listen: String? = null

        var listen_port: Int? = null

        var tcp_fast_open: Boolean? = null

        var tcp_multi_path: Boolean? = null

        var udp_fragment: Boolean? = null

        // Generate note: option type:  public Boolean UDPFragmentDefault;

        var udp_timeout: Long? = null

        var proxy_protocol: Boolean? = null

        var proxy_protocol_accept_no_header: Boolean? = null

        var detour: String? = null

        // Generate note: nested type InboundOptions
        var sniff: Boolean? = null

        var sniff_override_destination: Boolean? = null

        var sniff_timeout: Long? = null

        var domain_strategy: String? = null

        // End of public InboundOptions ;

        // End of public ListenOptions ;

        var users: MutableList<VMessUser>? = null

        var tls: InboundTLSOptions? = null

        var transport: V2RayTransportOptions? = null

    }

    class VMessUser : SingBoxOption() {

        var name: String? = null

        var uuid: String? = null

        var alterId: Int? = null

    }

    class VMessOutboundOptions : SingBoxOption() {

        // Generate note: nested type DialerOptions
        var detour: String? = null

        var bind_interface: String? = null

        var inet4_bind_address: String? = null

        var inet6_bind_address: String? = null

        var protect_path: String? = null

        var routing_mark: Int? = null

        var reuse_addr: Boolean? = null

        var connect_timeout: Long? = null

        var tcp_fast_open: Boolean? = null

        var tcp_multi_path: Boolean? = null

        var udp_fragment: Boolean? = null

        // Generate note: option type:  public Boolean UDPFragmentDefault;

        var domain_strategy: String? = null

        var fallback_delay: Long? = null

        // End of public DialerOptions ;

        // Generate note: nested type ServerOptions
        var server: String? = null

        var server_port: Int? = null

        // End of public ServerOptions ;

        var uuid: String? = null

        var security: String? = null

        var alter_id: Int? = null

        var global_padding: Boolean? = null

        var authenticated_length: Boolean? = null

        var network: String? = null

        var tls: OutboundTLSOptions? = null

        var packet_encoding: String? = null

        var multiplex: MultiplexOptions? = null

        var transport: V2RayTransportOptions? = null

    }

    class WireGuardOutboundOptions : SingBoxOption() {

        // Generate note: nested type DialerOptions
        var detour: String? = null

        var bind_interface: String? = null

        var inet4_bind_address: String? = null

        var inet6_bind_address: String? = null

        var protect_path: String? = null

        var routing_mark: Int? = null

        var reuse_addr: Boolean? = null

        var connect_timeout: Long? = null

        var tcp_fast_open: Boolean? = null

        var tcp_multi_path: Boolean? = null

        var udp_fragment: Boolean? = null

        // Generate note: option type:  public Boolean UDPFragmentDefault;

        var domain_strategy: String? = null

        var fallback_delay: Long? = null

        // End of public DialerOptions ;

        var system_interface: Boolean? = null

        var interface_name: String? = null

        // Generate note: Listable
        var local_address: MutableList<String>? = null

        var private_key: String? = null

        var peers: MutableList<WireGuardPeer>? = null

        // Generate note: nested type ServerOptions
        var server: String? = null

        var server_port: Int? = null

        // End of public ServerOptions ;

        var peer_public_key: String? = null

        var pre_shared_key: String? = null

        // Generate note: Base64 String
        var reserved: String? = null

        var workers: Int? = null

        var mtu: Int? = null

        var network: String? = null

    }

    class WireGuardPeer : SingBoxOption() {

        // Generate note: nested type ServerOptions
        var server: String? = null

        var server_port: Int? = null

        // End of public ServerOptions ;

        var public_key: String? = null

        var pre_shared_key: String? = null

        // Generate note: Listable
        var allowed_ips: MutableList<String>? = null

        // Generate note: Base64 String
        var reserved: String? = null

    }

    class Inbound_TunOptions : Inbound() {

        var interface_name: String? = null

        var mtu: Int? = null

        // sing-box 1.12 起替代 inet4_address/inet6_address 的合并字段
        var address: MutableList<String>? = null

        // Generate note: Listable
        var inet4_address: MutableList<String>? = null

        // Generate note: Listable
        var inet6_address: MutableList<String>? = null

        var auto_route: Boolean? = null

        var strict_route: Boolean? = null

        // Generate note: Listable
        var inet4_route_address: MutableList<String>? = null

        // Generate note: Listable
        var inet6_route_address: MutableList<String>? = null

        // Generate note: Listable
        var include_interface: MutableList<String>? = null

        // Generate note: Listable
        var exclude_interface: MutableList<String>? = null

        // Generate note: Listable
        var include_uid: MutableList<Int>? = null

        // Generate note: Listable
        var include_uid_range: MutableList<String>? = null

        // Generate note: Listable
        var exclude_uid: MutableList<Int>? = null

        // Generate note: Listable
        var exclude_uid_range: MutableList<String>? = null

        // Generate note: Listable
        var include_android_user: MutableList<Int>? = null

        // Generate note: Listable
        var include_package: MutableList<String>? = null

        // Generate note: Listable
        var exclude_package: MutableList<String>? = null

        var endpoint_independent_nat: Boolean? = null

        var udp_timeout: Long? = null

        var stack: String? = null

        var platform: TunPlatformOptions? = null

        // Generate note: nested type InboundOptions
        var sniff: Boolean? = null

        var sniff_override_destination: Boolean? = null

        var sniff_timeout: Long? = null

        var domain_strategy: String? = null

        // End of public InboundOptions ;

    }

    class Inbound_RedirectOptions : Inbound() {

        // Generate note: nested type ListenOptions
        var listen: String? = null

        var listen_port: Int? = null

        var tcp_fast_open: Boolean? = null

        var tcp_multi_path: Boolean? = null

        var udp_fragment: Boolean? = null


        var udp_timeout: Long? = null

        var proxy_protocol: Boolean? = null

        var proxy_protocol_accept_no_header: Boolean? = null

        var detour: String? = null

        // Generate note: nested type InboundOptions
        var sniff: Boolean? = null

        var sniff_override_destination: Boolean? = null

        var sniff_timeout: Long? = null

        var domain_strategy: String? = null

        // End of public InboundOptions ;

        // End of public ListenOptions ;

    }

    class Inbound_TProxyOptions : Inbound() {

        // Generate note: nested type ListenOptions
        var listen: String? = null

        var listen_port: Int? = null

        var tcp_fast_open: Boolean? = null

        var tcp_multi_path: Boolean? = null

        var udp_fragment: Boolean? = null


        var udp_timeout: Long? = null

        var proxy_protocol: Boolean? = null

        var proxy_protocol_accept_no_header: Boolean? = null

        var detour: String? = null

        // Generate note: nested type InboundOptions
        var sniff: Boolean? = null

        var sniff_override_destination: Boolean? = null

        var sniff_timeout: Long? = null

        var domain_strategy: String? = null

        // End of public InboundOptions ;

        // End of public ListenOptions ;

        var network: String? = null

    }

    class Inbound_DirectOptions : Inbound() {

        // Generate note: nested type ListenOptions
        var listen: String? = null

        var listen_port: Int? = null

        var tcp_fast_open: Boolean? = null

        var tcp_multi_path: Boolean? = null

        var udp_fragment: Boolean? = null


        var udp_timeout: Long? = null

        var proxy_protocol: Boolean? = null

        var proxy_protocol_accept_no_header: Boolean? = null

        var detour: String? = null

        // Generate note: nested type InboundOptions
        var sniff: Boolean? = null

        var sniff_override_destination: Boolean? = null

        var sniff_timeout: Long? = null

        var domain_strategy: String? = null

        // End of public InboundOptions ;

        // End of public ListenOptions ;

        var network: String? = null

        var override_address: String? = null

        var override_port: Int? = null

    }

    class Inbound_SocksOptions : Inbound() {

        // Generate note: nested type ListenOptions
        var listen: String? = null

        var listen_port: Int? = null

        var tcp_fast_open: Boolean? = null

        var tcp_multi_path: Boolean? = null

        var udp_fragment: Boolean? = null


        var udp_timeout: Long? = null

        var proxy_protocol: Boolean? = null

        var proxy_protocol_accept_no_header: Boolean? = null

        var detour: String? = null

        // Generate note: nested type InboundOptions
        var sniff: Boolean? = null

        var sniff_override_destination: Boolean? = null

        var sniff_timeout: Long? = null

        var domain_strategy: String? = null

        // End of public InboundOptions ;

        // End of public ListenOptions ;

        var users: MutableList<User>? = null

    }

    class Inbound_HTTPOptions : Inbound() {

        // Generate note: nested type ListenOptions
        var listen: String? = null

        var listen_port: Int? = null

        var tcp_fast_open: Boolean? = null

        var tcp_multi_path: Boolean? = null

        var udp_fragment: Boolean? = null


        var udp_timeout: Long? = null

        var proxy_protocol: Boolean? = null

        var proxy_protocol_accept_no_header: Boolean? = null

        var detour: String? = null

        // Generate note: nested type InboundOptions
        var sniff: Boolean? = null

        var sniff_override_destination: Boolean? = null

        var sniff_timeout: Long? = null

        var domain_strategy: String? = null

        // End of public InboundOptions ;

        // End of public ListenOptions ;

        var users: MutableList<User>? = null

        var set_system_proxy: Boolean? = null

        var tls: InboundTLSOptions? = null

    }

    class Inbound_MixedOptions : Inbound() {

        // Generate note: nested type ListenOptions
        var listen: String? = null

        var listen_port: Int? = null

        var tcp_fast_open: Boolean? = null

        var tcp_multi_path: Boolean? = null

        var udp_fragment: Boolean? = null


        var udp_timeout: Long? = null

        var proxy_protocol: Boolean? = null

        var proxy_protocol_accept_no_header: Boolean? = null

        var detour: String? = null

        // Generate note: nested type InboundOptions
        var sniff: Boolean? = null

        var sniff_override_destination: Boolean? = null

        var sniff_timeout: Long? = null

        var domain_strategy: String? = null

        // End of public InboundOptions ;

        // End of public ListenOptions ;

        var users: MutableList<User>? = null

        var set_system_proxy: Boolean? = null

        var tls: InboundTLSOptions? = null

    }

    class Inbound_ShadowsocksOptions : Inbound() {

        // Generate note: nested type ListenOptions
        var listen: String? = null

        var listen_port: Int? = null

        var tcp_fast_open: Boolean? = null

        var tcp_multi_path: Boolean? = null

        var udp_fragment: Boolean? = null


        var udp_timeout: Long? = null

        var proxy_protocol: Boolean? = null

        var proxy_protocol_accept_no_header: Boolean? = null

        var detour: String? = null

        // Generate note: nested type InboundOptions
        var sniff: Boolean? = null

        var sniff_override_destination: Boolean? = null

        var sniff_timeout: Long? = null

        var domain_strategy: String? = null

        // End of public InboundOptions ;

        // End of public ListenOptions ;

        var network: String? = null

        var method: String? = null

        var password: String? = null

        var users: MutableList<ShadowsocksUser>? = null

        var destinations: MutableList<ShadowsocksDestination>? = null

    }

    class Inbound_VMessOptions : Inbound() {

        // Generate note: nested type ListenOptions
        var listen: String? = null

        var listen_port: Int? = null

        var tcp_fast_open: Boolean? = null

        var tcp_multi_path: Boolean? = null

        var udp_fragment: Boolean? = null


        var udp_timeout: Long? = null

        var proxy_protocol: Boolean? = null

        var proxy_protocol_accept_no_header: Boolean? = null

        var detour: String? = null

        // Generate note: nested type InboundOptions
        var sniff: Boolean? = null

        var sniff_override_destination: Boolean? = null

        var sniff_timeout: Long? = null

        var domain_strategy: String? = null

        // End of public InboundOptions ;

        // End of public ListenOptions ;

        var users: MutableList<VMessUser>? = null

        var tls: InboundTLSOptions? = null

        var transport: V2RayTransportOptions? = null

    }

    class Inbound_TrojanOptions : Inbound() {

        // Generate note: nested type ListenOptions
        var listen: String? = null

        var listen_port: Int? = null

        var tcp_fast_open: Boolean? = null

        var tcp_multi_path: Boolean? = null

        var udp_fragment: Boolean? = null


        var udp_timeout: Long? = null

        var proxy_protocol: Boolean? = null

        var proxy_protocol_accept_no_header: Boolean? = null

        var detour: String? = null

        // Generate note: nested type InboundOptions
        var sniff: Boolean? = null

        var sniff_override_destination: Boolean? = null

        var sniff_timeout: Long? = null

        var domain_strategy: String? = null

        // End of public InboundOptions ;

        // End of public ListenOptions ;

        var users: MutableList<TrojanUser>? = null

        var tls: InboundTLSOptions? = null

        var fallback: ServerOptions? = null

        var fallback_for_alpn: MutableMap<String, ServerOptions>? = null

        var transport: V2RayTransportOptions? = null

    }

    class Inbound_NaiveOptions : Inbound() {

        // Generate note: nested type ListenOptions
        var listen: String? = null

        var listen_port: Int? = null

        var tcp_fast_open: Boolean? = null

        var tcp_multi_path: Boolean? = null

        var udp_fragment: Boolean? = null


        var udp_timeout: Long? = null

        var proxy_protocol: Boolean? = null

        var proxy_protocol_accept_no_header: Boolean? = null

        var detour: String? = null

        // Generate note: nested type InboundOptions
        var sniff: Boolean? = null

        var sniff_override_destination: Boolean? = null

        var sniff_timeout: Long? = null

        var domain_strategy: String? = null

        // End of public InboundOptions ;

        // End of public ListenOptions ;

        var users: MutableList<User>? = null

        var network: String? = null

        var tls: InboundTLSOptions? = null

    }

    class Inbound_HysteriaOptions : Inbound() {

        // Generate note: nested type ListenOptions
        var listen: String? = null

        var listen_port: Int? = null

        var tcp_fast_open: Boolean? = null

        var tcp_multi_path: Boolean? = null

        var udp_fragment: Boolean? = null


        var udp_timeout: Long? = null

        var proxy_protocol: Boolean? = null

        var proxy_protocol_accept_no_header: Boolean? = null

        var detour: String? = null

        // Generate note: nested type InboundOptions
        var sniff: Boolean? = null

        var sniff_override_destination: Boolean? = null

        var sniff_timeout: Long? = null

        var domain_strategy: String? = null

        // End of public InboundOptions ;

        // End of public ListenOptions ;

        var up: String? = null

        var up_mbps: Int? = null

        var down: String? = null

        var down_mbps: Int? = null

        var obfs: String? = null

        var users: MutableList<HysteriaUser>? = null

        var recv_window_conn: Long? = null

        var recv_window_client: Long? = null

        var max_conn_client: Int? = null

        var disable_mtu_discovery: Boolean? = null

        var tls: InboundTLSOptions? = null

    }

    class Inbound_ShadowTLSOptions : Inbound() {

        // Generate note: nested type ListenOptions
        var listen: String? = null

        var listen_port: Int? = null

        var tcp_fast_open: Boolean? = null

        var tcp_multi_path: Boolean? = null

        var udp_fragment: Boolean? = null


        var udp_timeout: Long? = null

        var proxy_protocol: Boolean? = null

        var proxy_protocol_accept_no_header: Boolean? = null

        var detour: String? = null

        // Generate note: nested type InboundOptions
        var sniff: Boolean? = null

        var sniff_override_destination: Boolean? = null

        var sniff_timeout: Long? = null

        var domain_strategy: String? = null

        // End of public InboundOptions ;

        // End of public ListenOptions ;

        var version: Int? = null

        var password: String? = null

        var users: MutableList<ShadowTLSUser>? = null

        var handshake: ShadowTLSHandshakeOptions? = null

        var handshake_for_server_name: MutableMap<String, ShadowTLSHandshakeOptions>? = null

        var strict_mode: Boolean? = null

    }

    class Inbound_VLESSOptions : Inbound() {

        // Generate note: nested type ListenOptions
        var listen: String? = null

        var listen_port: Int? = null

        var tcp_fast_open: Boolean? = null

        var tcp_multi_path: Boolean? = null

        var udp_fragment: Boolean? = null


        var udp_timeout: Long? = null

        var proxy_protocol: Boolean? = null

        var proxy_protocol_accept_no_header: Boolean? = null

        var detour: String? = null

        // Generate note: nested type InboundOptions
        var sniff: Boolean? = null

        var sniff_override_destination: Boolean? = null

        var sniff_timeout: Long? = null

        var domain_strategy: String? = null

        // End of public InboundOptions ;

        // End of public ListenOptions ;

        var users: MutableList<VLESSUser>? = null

        var tls: InboundTLSOptions? = null

        var transport: V2RayTransportOptions? = null

    }

    class Inbound_TUICOptions : Inbound() {

        // Generate note: nested type ListenOptions
        var listen: String? = null

        var listen_port: Int? = null

        var tcp_fast_open: Boolean? = null

        var tcp_multi_path: Boolean? = null

        var udp_fragment: Boolean? = null


        var udp_timeout: Long? = null

        var proxy_protocol: Boolean? = null

        var proxy_protocol_accept_no_header: Boolean? = null

        var detour: String? = null

        // Generate note: nested type InboundOptions
        var sniff: Boolean? = null

        var sniff_override_destination: Boolean? = null

        var sniff_timeout: Long? = null

        var domain_strategy: String? = null

        // End of public InboundOptions ;

        // End of public ListenOptions ;

        var users: MutableList<TUICUser>? = null

        var congestion_control: String? = null

        var auth_timeout: Long? = null

        var zero_rtt_handshake: Boolean? = null

        var heartbeat: Long? = null

        var tls: InboundTLSOptions? = null

    }

    class Inbound_Hysteria2Options : Inbound() {

        // Generate note: nested type ListenOptions
        var listen: String? = null

        var listen_port: Int? = null

        var tcp_fast_open: Boolean? = null

        var tcp_multi_path: Boolean? = null

        var udp_fragment: Boolean? = null


        var udp_timeout: Long? = null

        var proxy_protocol: Boolean? = null

        var proxy_protocol_accept_no_header: Boolean? = null

        var detour: String? = null

        // Generate note: nested type InboundOptions
        var sniff: Boolean? = null

        var sniff_override_destination: Boolean? = null

        var sniff_timeout: Long? = null

        var domain_strategy: String? = null

        // End of public InboundOptions ;

        // End of public ListenOptions ;

        var up_mbps: Int? = null

        var down_mbps: Int? = null

        var obfs: Hysteria2Obfs? = null

        var users: MutableList<Hysteria2User>? = null

        var ignore_client_bandwidth: Boolean? = null

        var tls: InboundTLSOptions? = null

        var masquerade: String? = null

    }

    class Outbound_DirectOptions : Outbound() {

        // Generate note: nested type DialerOptions
        var detour: String? = null

        var bind_interface: String? = null

        var inet4_bind_address: String? = null

        var inet6_bind_address: String? = null

        var protect_path: String? = null

        var routing_mark: Int? = null

        var reuse_addr: Boolean? = null

        var connect_timeout: Long? = null

        var tcp_fast_open: Boolean? = null

        var tcp_multi_path: Boolean? = null

        var udp_fragment: Boolean? = null


        var domain_strategy: String? = null

        var fallback_delay: Long? = null

        // End of public DialerOptions ;

        var override_address: String? = null

        var override_port: Int? = null

        var proxy_protocol: Int? = null

    }

    class Outbound_SocksOptions : Outbound() {

        // Generate note: nested type DialerOptions
        var detour: String? = null

        var bind_interface: String? = null

        var inet4_bind_address: String? = null

        var inet6_bind_address: String? = null

        var protect_path: String? = null

        var routing_mark: Int? = null

        var reuse_addr: Boolean? = null

        var connect_timeout: Long? = null

        var tcp_fast_open: Boolean? = null

        var tcp_multi_path: Boolean? = null

        var udp_fragment: Boolean? = null


        var domain_strategy: String? = null

        var fallback_delay: Long? = null

        // End of public DialerOptions ;

        // Generate note: nested type ServerOptions
        var server: String? = null

        var server_port: Int? = null

        // End of public ServerOptions ;

        var version: String? = null

        var username: String? = null

        var password: String? = null

        var network: String? = null

        var udp_over_tcp: UDPOverTCPOptions? = null

    }

    class Outbound_HTTPOptions : Outbound() {

        // Generate note: nested type DialerOptions
        var detour: String? = null

        var bind_interface: String? = null

        var inet4_bind_address: String? = null

        var inet6_bind_address: String? = null

        var protect_path: String? = null

        var routing_mark: Int? = null

        var reuse_addr: Boolean? = null

        var connect_timeout: Long? = null

        var tcp_fast_open: Boolean? = null

        var tcp_multi_path: Boolean? = null

        var udp_fragment: Boolean? = null


        var domain_strategy: String? = null

        var fallback_delay: Long? = null

        // End of public DialerOptions ;

        // Generate note: nested type ServerOptions
        var server: String? = null

        var server_port: Int? = null

        // End of public ServerOptions ;

        var username: String? = null

        var password: String? = null

        var tls: OutboundTLSOptions? = null

        var path: String? = null

        var headers: MutableMap<String, String>? = null

    }

    class Outbound_ShadowsocksOptions : Outbound() {

        // Generate note: nested type DialerOptions
        var detour: String? = null

        var bind_interface: String? = null

        var inet4_bind_address: String? = null

        var inet6_bind_address: String? = null

        var protect_path: String? = null

        var routing_mark: Int? = null

        var reuse_addr: Boolean? = null

        var connect_timeout: Long? = null

        var tcp_fast_open: Boolean? = null

        var tcp_multi_path: Boolean? = null

        var udp_fragment: Boolean? = null


        var domain_strategy: String? = null

        var fallback_delay: Long? = null

        // End of public DialerOptions ;

        // Generate note: nested type ServerOptions
        var server: String? = null

        var server_port: Int? = null

        // End of public ServerOptions ;

        var method: String? = null

        var password: String? = null

        var plugin: String? = null

        var plugin_opts: String? = null

        var network: String? = null

        var udp_over_tcp: UDPOverTCPOptions? = null

        var multiplex: MultiplexOptions? = null

    }

    class Outbound_VMessOptions : Outbound() {

        // Generate note: nested type DialerOptions
        var detour: String? = null

        var bind_interface: String? = null

        var inet4_bind_address: String? = null

        var inet6_bind_address: String? = null

        var protect_path: String? = null

        var routing_mark: Int? = null

        var reuse_addr: Boolean? = null

        var connect_timeout: Long? = null

        var tcp_fast_open: Boolean? = null

        var tcp_multi_path: Boolean? = null

        var udp_fragment: Boolean? = null


        var domain_strategy: String? = null

        var fallback_delay: Long? = null

        // End of public DialerOptions ;

        // Generate note: nested type ServerOptions
        var server: String? = null

        var server_port: Int? = null

        // End of public ServerOptions ;

        var uuid: String? = null

        var security: String? = null

        var alter_id: Int? = null

        var global_padding: Boolean? = null

        var authenticated_length: Boolean? = null

        var network: String? = null

        var tls: OutboundTLSOptions? = null

        var packet_encoding: String? = null

        var multiplex: MultiplexOptions? = null

        var transport: V2RayTransportOptions? = null

    }

    class Outbound_TrojanOptions : Outbound() {

        // Generate note: nested type DialerOptions
        var detour: String? = null

        var bind_interface: String? = null

        var inet4_bind_address: String? = null

        var inet6_bind_address: String? = null

        var protect_path: String? = null

        var routing_mark: Int? = null

        var reuse_addr: Boolean? = null

        var connect_timeout: Long? = null

        var tcp_fast_open: Boolean? = null

        var tcp_multi_path: Boolean? = null

        var udp_fragment: Boolean? = null


        var domain_strategy: String? = null

        var fallback_delay: Long? = null

        // End of public DialerOptions ;

        // Generate note: nested type ServerOptions
        var server: String? = null

        var server_port: Int? = null

        // End of public ServerOptions ;

        var password: String? = null

        var network: String? = null

        var tls: OutboundTLSOptions? = null

        var multiplex: MultiplexOptions? = null

        var transport: V2RayTransportOptions? = null

    }

    class Outbound_WireGuardOptions : Outbound() {

        // Generate note: nested type DialerOptions
        var detour: String? = null

        var bind_interface: String? = null

        var inet4_bind_address: String? = null

        var inet6_bind_address: String? = null

        var protect_path: String? = null

        var routing_mark: Int? = null

        var reuse_addr: Boolean? = null

        var connect_timeout: Long? = null

        var tcp_fast_open: Boolean? = null

        var tcp_multi_path: Boolean? = null

        var udp_fragment: Boolean? = null


        var domain_strategy: String? = null

        var fallback_delay: Long? = null

        // End of public DialerOptions ;

        var system_interface: Boolean? = null

        var interface_name: String? = null

        // Generate note: Listable
        var local_address: MutableList<String>? = null

        var private_key: String? = null

        var peers: MutableList<WireGuardPeer>? = null

        // Generate note: nested type ServerOptions
        var server: String? = null

        var server_port: Int? = null

        // End of public ServerOptions ;

        var peer_public_key: String? = null

        var pre_shared_key: String? = null

        // Generate note: Base64 String
        var reserved: String? = null

        var workers: Int? = null

        var mtu: Int? = null

        var network: String? = null

    }

    class Outbound_HysteriaOptions : Outbound() {

        // Generate note: nested type DialerOptions
        var detour: String? = null

        var bind_interface: String? = null

        var inet4_bind_address: String? = null

        var inet6_bind_address: String? = null

        var protect_path: String? = null

        var routing_mark: Int? = null

        var reuse_addr: Boolean? = null

        var connect_timeout: Long? = null

        var tcp_fast_open: Boolean? = null

        var tcp_multi_path: Boolean? = null

        var udp_fragment: Boolean? = null


        var domain_strategy: String? = null

        var fallback_delay: Long? = null

        // End of public DialerOptions ;

        // Generate note: nested type ServerOptions
        var server: String? = null

        var server_port: Int? = null

        // End of public ServerOptions ;

        var up: String? = null

        var up_mbps: Int? = null

        var down: String? = null

        var down_mbps: Int? = null

        var obfs: String? = null

        // Generate note: Base64 String
        var auth: String? = null

        var auth_str: String? = null

        var recv_window_conn: Long? = null

        var recv_window: Long? = null

        var disable_mtu_discovery: Boolean? = null

        var network: String? = null

        var tls: OutboundTLSOptions? = null

        var server_ports: MutableList<String>? = null

        var hop_interval: String? = null

    }

    class Outbound_TorOptions : Outbound() {

        // Generate note: nested type DialerOptions
        var detour: String? = null

        var bind_interface: String? = null

        var inet4_bind_address: String? = null

        var inet6_bind_address: String? = null

        var protect_path: String? = null

        var routing_mark: Int? = null

        var reuse_addr: Boolean? = null

        var connect_timeout: Long? = null

        var tcp_fast_open: Boolean? = null

        var tcp_multi_path: Boolean? = null

        var udp_fragment: Boolean? = null


        var domain_strategy: String? = null

        var fallback_delay: Long? = null

        // End of public DialerOptions ;

        var executable_path: String? = null

        var extra_args: MutableList<String>? = null

        var data_directory: String? = null

        var torrc: MutableMap<String, String>? = null

    }

    class Outbound_SSHOptions : Outbound() {

        // Generate note: nested type DialerOptions
        var detour: String? = null

        var bind_interface: String? = null

        var inet4_bind_address: String? = null

        var inet6_bind_address: String? = null

        var protect_path: String? = null

        var routing_mark: Int? = null

        var reuse_addr: Boolean? = null

        var connect_timeout: Long? = null

        var tcp_fast_open: Boolean? = null

        var tcp_multi_path: Boolean? = null

        var udp_fragment: Boolean? = null


        var domain_strategy: String? = null

        var fallback_delay: Long? = null

        // End of public DialerOptions ;

        // Generate note: nested type ServerOptions
        var server: String? = null

        var server_port: Int? = null

        // End of public ServerOptions ;

        var user: String? = null

        var password: String? = null

        var private_key: String? = null

        var private_key_path: String? = null

        var private_key_passphrase: String? = null

        // Generate note: Listable
        var host_key: MutableList<String>? = null

        // Generate note: Listable
        var host_key_algorithms: MutableList<String>? = null

        var client_version: String? = null

    }

    class Outbound_ShadowTLSOptions : Outbound() {

        // Generate note: nested type DialerOptions
        var detour: String? = null

        var bind_interface: String? = null

        var inet4_bind_address: String? = null

        var inet6_bind_address: String? = null

        var protect_path: String? = null

        var routing_mark: Int? = null

        var reuse_addr: Boolean? = null

        var connect_timeout: Long? = null

        var tcp_fast_open: Boolean? = null

        var tcp_multi_path: Boolean? = null

        var udp_fragment: Boolean? = null


        var domain_strategy: String? = null

        var fallback_delay: Long? = null

        // End of public DialerOptions ;

        // Generate note: nested type ServerOptions
        var server: String? = null

        var server_port: Int? = null

        // End of public ServerOptions ;

        var version: Int? = null

        var password: String? = null

        var tls: OutboundTLSOptions? = null

    }

    class Outbound_ShadowsocksROptions : Outbound() {

        // Generate note: nested type DialerOptions
        var detour: String? = null

        var bind_interface: String? = null

        var inet4_bind_address: String? = null

        var inet6_bind_address: String? = null

        var protect_path: String? = null

        var routing_mark: Int? = null

        var reuse_addr: Boolean? = null

        var connect_timeout: Long? = null

        var tcp_fast_open: Boolean? = null

        var tcp_multi_path: Boolean? = null

        var udp_fragment: Boolean? = null


        var domain_strategy: String? = null

        var fallback_delay: Long? = null

        // End of public DialerOptions ;

        // Generate note: nested type ServerOptions
        var server: String? = null

        var server_port: Int? = null

        // End of public ServerOptions ;

        var method: String? = null

        var password: String? = null

        var obfs: String? = null

        var obfs_param: String? = null

        var protocol: String? = null

        var protocol_param: String? = null

        var network: String? = null

    }

    class Outbound_VLESSOptions : Outbound() {

        // Generate note: nested type DialerOptions
        var detour: String? = null

        var bind_interface: String? = null

        var inet4_bind_address: String? = null

        var inet6_bind_address: String? = null

        var protect_path: String? = null

        var routing_mark: Int? = null

        var reuse_addr: Boolean? = null

        var connect_timeout: Long? = null

        var tcp_fast_open: Boolean? = null

        var tcp_multi_path: Boolean? = null

        var udp_fragment: Boolean? = null


        var domain_strategy: String? = null

        var fallback_delay: Long? = null

        // End of public DialerOptions ;

        // Generate note: nested type ServerOptions
        var server: String? = null

        var server_port: Int? = null

        // End of public ServerOptions ;

        var uuid: String? = null

        var flow: String? = null

        var network: String? = null

        var tls: OutboundTLSOptions? = null

        var multiplex: MultiplexOptions? = null

        var transport: V2RayTransportOptions? = null

        var packet_encoding: String? = null

    }

    class Outbound_TUICOptions : Outbound() {

        // Generate note: nested type DialerOptions
        var detour: String? = null

        var bind_interface: String? = null

        var inet4_bind_address: String? = null

        var inet6_bind_address: String? = null

        var protect_path: String? = null

        var routing_mark: Int? = null

        var reuse_addr: Boolean? = null

        var connect_timeout: Long? = null

        var tcp_fast_open: Boolean? = null

        var tcp_multi_path: Boolean? = null

        var udp_fragment: Boolean? = null


        var domain_strategy: String? = null

        var fallback_delay: Long? = null

        // End of public DialerOptions ;

        // Generate note: nested type ServerOptions
        var server: String? = null

        var server_port: Int? = null

        // End of public ServerOptions ;

        var uuid: String? = null

        var password: String? = null

        var congestion_control: String? = null

        var udp_relay_mode: String? = null

        var udp_over_stream: Boolean? = null

        var zero_rtt_handshake: Boolean? = null

        var heartbeat: Long? = null

        var network: String? = null

        var tls: OutboundTLSOptions? = null

    }

    class Outbound_Hysteria2Options : Outbound() {

        // Generate note: nested type DialerOptions
        var detour: String? = null

        var bind_interface: String? = null

        var inet4_bind_address: String? = null

        var inet6_bind_address: String? = null

        var protect_path: String? = null

        var routing_mark: Int? = null

        var reuse_addr: Boolean? = null

        var connect_timeout: Long? = null

        var tcp_fast_open: Boolean? = null

        var tcp_multi_path: Boolean? = null

        var udp_fragment: Boolean? = null


        var domain_strategy: String? = null

        var fallback_delay: Long? = null

        // End of public DialerOptions ;

        // Generate note: nested type ServerOptions
        var server: String? = null

        var server_port: Int? = null

        // End of public ServerOptions ;

        var up_mbps: Int? = null

        var down_mbps: Int? = null

        var obfs: Hysteria2Obfs? = null

        var password: String? = null

        var network: String? = null

        var tls: OutboundTLSOptions? = null

        var server_ports: MutableList<String>? = null

        var hop_interval: String? = null

        var hop_interval_max: String? = null

        var bbr_profile: String? = null

        var disable_chrome_parrot: Boolean? = null

        var brutal_debug: Boolean? = null

    }

    class Outbound_SelectorOptions : Outbound() {

        var outbounds: MutableList<String>? = null

        @SerializedName("default")
        var default_: String? = null

    }

    class Outbound_URLTestOptions : Outbound() {

        var outbounds: MutableList<String>? = null

        var url: String? = null

        var interval: Long? = null

        var tolerance: Int? = null

    }

    class Rule_DefaultOptions : Rule() {

        // Generate note: Listable
        var inbound: MutableList<String>? = null

        var ip_version: Int? = null

        // Generate note: Listable
        var network: MutableList<String>? = null

        // Generate note: Listable
        var auth_user: MutableList<String>? = null

        // Generate note: Listable
        var protocol: MutableList<String>? = null

        // Generate note: Listable
        var domain: MutableList<String>? = null

        // Generate note: Listable
        var domain_suffix: MutableList<String>? = null

        // Generate note: Listable
        var domain_keyword: MutableList<String>? = null

        // Generate note: Listable
        var domain_regex: MutableList<String>? = null

        var rule_set: MutableList<String>? = null

        var source_ip_is_private: Boolean? = null

        var ip_is_private: Boolean? = null

        // Generate note: Listable
        var source_ip_cidr: MutableList<String>? = null

        // Generate note: Listable
        var ip_cidr: MutableList<String>? = null

        // Generate note: Listable
        var source_port: MutableList<Int>? = null

        // Generate note: Listable
        var source_port_range: MutableList<String>? = null

        // Generate note: Listable
        var port: MutableList<Int>? = null

        // Generate note: Listable
        var port_range: MutableList<String>? = null

        // Generate note: Listable
        var process_name: MutableList<String>? = null

        // Generate note: Listable
        var process_path: MutableList<String>? = null

        // Generate note: Listable
        var package_name: MutableList<String>? = null

        // Generate note: Listable
        var user: MutableList<String>? = null

        // Generate note: Listable
        var user_id: MutableList<Int>? = null

        var clash_mode: String? = null

        var invert: Boolean? = null

        var action: String? = null

        var outbound: String? = null

        // sing-box 1.13 resolve 规则动作使用（RouteActionResolve.strategy）
        var strategy: String? = null

        // sing-box 1.13 sniff 规则动作使用（RouteActionSniff.timeout / sniffer）
        var timeout: String? = null

        var sniffer: MutableList<String>? = null

    }

    class DNSRule_DefaultOptions : DNSRule() {

        // Generate note: Listable
        var inbound: MutableList<String>? = null

        var ip_version: Int? = null

        // Generate note: Listable
        var query_type: MutableList<String>? = null

        // Generate note: Listable
        var network: MutableList<String>? = null

        // Generate note: Listable
        var auth_user: MutableList<String>? = null

        // Generate note: Listable
        var protocol: MutableList<String>? = null

        // Generate note: Listable
        var domain: MutableList<String>? = null

        // Generate note: Listable
        var domain_suffix: MutableList<String>? = null

        // Generate note: Listable
        var domain_keyword: MutableList<String>? = null

        // Generate note: Listable
        var domain_regex: MutableList<String>? = null

        var rule_set: MutableList<String>? = null

        // Generate note: Listable
        var source_ip_cidr: MutableList<String>? = null

        // Generate note: Listable
        var source_port: MutableList<Int>? = null

        // Generate note: Listable
        var source_port_range: MutableList<String>? = null

        // Generate note: Listable
        var port: MutableList<Int>? = null

        // Generate note: Listable
        var port_range: MutableList<String>? = null

        // Generate note: Listable
        var process_name: MutableList<String>? = null

        // Generate note: Listable
        var process_path: MutableList<String>? = null

        // Generate note: Listable
        var package_name: MutableList<String>? = null

        // Generate note: Listable
        var user: MutableList<String>? = null

        // Generate note: Listable
        var user_id: MutableList<Int>? = null

        // Generate note: Listable
        var outbound: MutableList<String>? = null

        var clash_mode: String? = null

        var invert: Boolean? = null

        var server: String? = null

        var disable_cache: Boolean? = null

        var rewrite_ttl: Int? = null

        // sing-box 1.13+ DNS 规则动作（reject 等，替代已删除的 dns-block 服务器）
        var action: String? = null

        var rcode: String? = null

    }

    class V2RayTransportOptions_HTTPOptions : V2RayTransportOptions() {

        // Generate note: Listable
        var host: MutableList<String>? = null

        var path: String? = null

        var method: String? = null

        var headers: MutableMap<String, String>? = null

        var idle_timeout: Long? = null

        var ping_timeout: Long? = null

    }

    class V2RayTransportOptions_WebsocketOptions : V2RayTransportOptions() {

        var path: String? = null

        var headers: MutableMap<String, String>? = null

        var max_early_data: Int? = null

        var early_data_header_name: String? = null

    }


    class V2RayTransportOptions_GRPCOptions : V2RayTransportOptions() {

        var service_name: String? = null

        var idle_timeout: Long? = null

        var ping_timeout: Long? = null

        var permit_without_stream: Boolean? = null


    }

    class V2RayTransportOptions_HTTPUpgradeOptions : V2RayTransportOptions() {

        var host: String? = null

        var path: String? = null


    }

    class V2RayTransportOptions_XHTTPOptions : V2RayTransportOptions() {

        var mode: String? = null

        var host: String? = null

        var path: String? = null

        // Advanced field from extra config
        var download: JsonElement? = null
        var xmux: JsonElement? = null
        var headers: MutableMap<String, String>? = null
        var x_padding_bytes: JsonElement? = null
        var no_grpc_header: JsonElement? = null
        var no_sse_header: JsonElement? = null
        var sc_max_each_post_bytes: JsonElement? = null
        var sc_min_posts_interval_ms: JsonElement? = null
        var sc_max_buffered_posts: JsonElement? = null
        var sc_stream_up_server_secs: JsonElement? = null
        var x_padding_obfs_mode: JsonElement? = null
        var x_padding_key: JsonElement? = null
        var x_padding_header: JsonElement? = null
        var x_padding_placement: JsonElement? = null
        var x_padding_method: JsonElement? = null
        var uplink_http_method: JsonElement? = null
        var session_placement: JsonElement? = null
        var session_key: JsonElement? = null
        var seq_placement: JsonElement? = null
        var seq_key: JsonElement? = null
        var uplink_data_placement: JsonElement? = null
        var uplink_data_key: JsonElement? = null
        var uplink_chunk_size: JsonElement? = null

    }

    // sing-box Options 生成器已经坏了，以下是从 husi 抄的

    class Outbound_AnyTLSOptions : Outbound() {

        // Generate note: nested type DialerOptions
        var detour: String? = null

        var bind_interface: String? = null

        var inet4_bind_address: String? = null

        var inet6_bind_address: String? = null

        var protect_path: String? = null

        var routing_mark: Int? = null

        var reuse_addr: Boolean? = null

        var connect_timeout: String? = null

        var tcp_fast_open: Boolean? = null

        var tcp_multi_path: Boolean? = null

        var udp_fragment: Boolean? = null

        var domain_strategy: String? = null

        var network_strategy: String? = null

        var network_type: MutableList<String>? = null

        var fallback_network_type: MutableList<String>? = null

        var fallback_delay: String? = null

        // Generate note: nested type ServerOptions
        var server: String? = null

        var server_port: Int? = null

        // Generate note: nested type OutboundTLSOptionsContainer
        var tls: OutboundTLSOptions? = null

        var password: String? = null

        var idle_session_check_interval: String? = null

        var idle_session_timeout: String? = null

        var min_idle_session: Int? = null

    }

}
