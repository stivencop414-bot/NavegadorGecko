package com.ejemplo.navegador

/** Names are presentation data; never paths, identifiers for containers or network rules. */
object TabGroupRules {
    /** Colores de grupo legibles en tema claro y oscuro. */
    private val COLORS = intArrayOf(
        0xFF6366F1.toInt(), // índigo
        0xFF10B981.toInt(), // esmeralda
        0xFFF59E0B.toInt(), // ámbar
        0xFFEC4899.toInt(), // rosa
        0xFF0EA5E9.toInt(), // celeste
        0xFFEF4444.toInt(), // rojo
        0xFF8B5CF6.toInt(), // violeta
        0xFF14B8A6.toInt()  // turquesa
    )

    fun name(value: String?): String? = value?.filterNot { it.isISOControl() }?.trim()
        ?.take(40)?.takeIf { it.isNotBlank() }

    /** Color estable para un grupo: el mismo nombre conserva su color entre sesiones. */
    fun color(name: String?): Int {
        val clean = name(name) ?: return COLORS[0]
        var hash = 0
        for (ch in clean.lowercase()) hash = hash * 31 + ch.code
        return COLORS[Math.floorMod(hash, COLORS.size)]
    }
}
