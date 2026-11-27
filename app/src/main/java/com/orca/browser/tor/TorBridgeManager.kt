package com.orca.browser.tor

object TorBridgeManager {

    val OBFS4_BRIDGES = listOf(
        "obfs4 85.17.30.79:443 FC259A04A328A07FED1413E9FC6526530D9FD87A cert=RutxZlu8BtyP+y0NX7bAVD41+J/qXNhHUrKjFkRSdiBAhIHIQLhKQ2HxESAKZprn/lR3KA iat-mode=0",
        "obfs4 192.95.36.142:443 CDF2E852BF539B82BD10E27E9115A31734E378C2 cert=qUVQ0srL1JI/vO6V6m/24anYXiJD3QP2HgzUKQtQ7GRqqUvs7P+tG43RtAqdhLOALP7DJQ iat-mode=1",
        "obfs4 144.217.20.138:80 FB70B257C162BF1038CA669D568D76F5B7F0BABB cert=vYIV5MgrghGQvZPIi1tJwnzorMgqgmlKaB77Y3Z9Q/v94wZBOAXkW+fdx4aSxLVnKO+xNw iat-mode=0",
        "obfs4 51.222.13.177:80 5EDAC3B810E12B01F6FD8050D2FD3E277B289A08 cert=2uplIpLQ0q9+0qMFrK5pkaYRDOe460LL9WHBvatgkuRr/SL31wBOEupaMMJ6koRE6Ld0ew iat-mode=0"
    )

    val MEEK_BRIDGES = listOf(
        "meek_lite 192.0.2.18:80 BE776A53492E1E044A26F17306E1BC46A55A1625 url=https://meek.azureedge.net/ front=ajax.aspnetcdn.com"
    )

    val SNOWFLAKE_BRIDGES = listOf(
        "snowflake 192.0.2.3:80 2B280B23E1107BB62ABFC40DDCC8824814F80A72 fingerprint=2B280B23E1107BB62ABFC40DDCC8824814F80A72 url=https://1098762253.rsc.cdn77.org/ front=www.cdn77.org ice=stun:stun.l.google.com:19302"
    )

    fun getBridgesForType(type: String): List<String> {
        return when (type.lowercase()) {
            "obfs4" -> OBFS4_BRIDGES
            "meek-azure", "meek_lite" -> MEEK_BRIDGES
            "snowflake" -> SNOWFLAKE_BRIDGES
            else -> emptyList()
        }
    }

    fun getAllBridgeTypes(): List<String> = listOf("obfs4", "meek-azure", "snowflake", "none")

    fun toPluggableTransportName(type: String): String? {
        return when (type.lowercase()) {
            "obfs4" -> "obfs4"
            "meek-azure", "meek_lite" -> "meek_lite"
            "snowflake" -> "snowflake"
            else -> null
        }
    }
}
