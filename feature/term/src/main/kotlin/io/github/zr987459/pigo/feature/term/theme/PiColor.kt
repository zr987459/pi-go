package io.github.zr987459.pigo.feature.term.theme

import kotlin.math.PI
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** sRGB 颜色（0–255）。 */
data class Rgb(val r: Int, val g: Int, val b: Int) {
    val argb: Long get() = 0xFF000000L or (r.toLong() shl 16) or (g.toLong() shl 8) or b.toLong()
    fun hex(): String = "#%02x%02x%02x".format(r, g, b)
}

/**
 * pi 主题颜色解析，逐行移植自 `@earendil-works/pi-tui` 1.5.0 的 `colors.js` / `oklab.js`（MIT，来源见 docs/notices/r3-term.md）：
 * 支持 `#rgb` / `#rrggbb`、`oklch(L C h)`、`okhsl(h s l)` 与 0–255 的 ANSI 索引色。空串表示终端默认色（返回 null）。
 */
object PiColor {
    private const val NUM = """[+-]?(?:\d+(?:\.\d*)?|\.\d+)(?:e[+-]?\d+)?"""
    private val HEX = Regex("""^#([\da-fA-F]{3}|[\da-fA-F]{6})$""")
    private val OKLCH = Regex("""^oklch\(\s*($NUM)(%)?\s+($NUM)\s+($NUM)(?:deg)?\s*\)$""", RegexOption.IGNORE_CASE)
    private val OKHSL = Regex("""^okhsl\(\s*($NUM)(?:deg)?\s+($NUM)(%)?\s+($NUM)(%)?\s*\)$""", RegexOption.IGNORE_CASE)

    fun parse(value: String): Rgb? {
        if (value.isEmpty()) return null
        HEX.matchEntire(value)?.let { m ->
            val d = m.groupValues[1].let { if (it.length == 3) it.map { c -> "$c$c" }.joinToString("") else it }
            return Rgb(d.substring(0, 2).toInt(16), d.substring(2, 4).toInt(16), d.substring(4, 6).toInt(16))
        }
        OKLCH.matchEntire(value)?.let { m ->
            val l = m.groupValues[1].toDouble() / (if (m.groupValues[2].isNotEmpty()) 100.0 else 1.0)
            require(l in 0.0..1.0) { "l must be between 0 and 1: $l" }
            val h = ((m.groupValues[4].toDouble() % 360) + 360) % 360
            return oklchToRgb(l, m.groupValues[3].toDouble(), h)
        }
        OKHSL.matchEntire(value)?.let { m ->
            val s = m.groupValues[2].toDouble() / (if (m.groupValues[3].isNotEmpty()) 100.0 else 1.0)
            val l = m.groupValues[4].toDouble() / (if (m.groupValues[5].isNotEmpty()) 100.0 else 1.0)
            require(s in 0.0..1.0 && l in 0.0..1.0) { "okhsl out of range: $value" }
            return okhslToRgb(m.groupValues[1].toDouble(), s, l)
        }
        throw IllegalArgumentException("Invalid color value: $value")
    }

    fun indexed(index: Int): Rgb {
        require(index in 0..255) { "ANSI color index must be an integer from 0 to 255: $index" }
        if (index < 16) return BASIC[index]
        if (index < 232) {
            val c = index - 16
            return Rgb(CUBE[c / 36], CUBE[(c % 36) / 6], CUBE[c % 6])
        }
        val g = 8 + (index - 232) * 10
        return Rgb(g, g, g)
    }

    private val BASIC = listOf(
        Rgb(0, 0, 0), Rgb(128, 0, 0), Rgb(0, 128, 0), Rgb(128, 128, 0), Rgb(0, 0, 128), Rgb(128, 0, 128), Rgb(0, 128, 128), Rgb(192, 192, 192),
        Rgb(128, 128, 128), Rgb(255, 0, 0), Rgb(0, 255, 0), Rgb(255, 255, 0), Rgb(0, 0, 255), Rgb(255, 0, 255), Rgb(0, 255, 255), Rgb(255, 255, 255),
    )
    private val CUBE = intArrayOf(0, 95, 135, 175, 215, 255)

    // ---- oklab.js ----
    private fun mul(m: Array<DoubleArray>, v: DoubleArray) = DoubleArray(3) { i -> m[i][0] * v[0] + m[i][1] * v[1] + m[i][2] * v[2] }

    private val LAB_TO_LMS = arrayOf(
        doubleArrayOf(1.0, 0.3963377773761749, 0.2158037573099136),
        doubleArrayOf(1.0, -0.1055613458156586, -0.0638541728258133),
        doubleArrayOf(1.0, -0.0894841775298119, -1.2914855480194092),
    )
    private val LMS_TO_LINEAR_SRGB = arrayOf(
        doubleArrayOf(4.0767416360759583, -3.3077115392580629, 0.2309699031821043),
        doubleArrayOf(-1.2684379732850315, 2.6097573492876882, -0.341319376002657),
        doubleArrayOf(-0.0041960761386756, -0.7034186179359362, 1.7076146940746117),
    )
    private val SAT_FIT = arrayOf(
        doubleArrayOf(-1.8817031, -0.80936501) to doubleArrayOf(1.19086277, 1.76576728, 0.59662641, 0.75515197, 0.56771245),
        doubleArrayOf(1.8144408, -1.19445267) to doubleArrayOf(0.73956515, -0.45954404, 0.08285427, 0.12541073, -0.14503204),
        doubleArrayOf(0.13110758, 1.81333971) to doubleArrayOf(1.35733652, -0.00915799, -1.1513021, -0.50559606, 0.00692167),
    )
    private const val K1 = 0.206
    private const val K2 = 0.03
    private const val K3 = (1 + K1) / (1 + K2)

    private fun okhslToOklabL(x: Double) = (x * x + K1 * x) / (K3 * (x + K2))
    private fun linearToSrgb(v: Double) = if (v > 0.0031308) 1.055 * v.pow(1 / 2.4) - 0.055 else 12.92 * v
    private fun cube(v: Double) = v * v * v

    fun oklabToLinear(lab: DoubleArray): DoubleArray = mul(LMS_TO_LINEAR_SRGB, mul(LAB_TO_LMS, lab).map { cube(it) }.toDoubleArray())

    fun linearToRgb(lin: DoubleArray): Rgb {
        val c = lin.map { jsRound(min(1.0, max(0.0, linearToSrgb(it))) * 255) }
        return Rgb(c[0], c[1], c[2])
    }

    /** JS `Math.round`：.5 向正无穷取整。 */
    private fun jsRound(v: Double): Int = floor(v + 0.5).toInt()

    private fun slopes(a: Double, b: Double) = DoubleArray(3) { i -> LAB_TO_LMS[i][1] * a + LAB_TO_LMS[i][2] * b }

    private fun maxSaturation(a: Double, b: Double): Double {
        val ch = SAT_FIT.indices.first { i -> i == 2 || SAT_FIT[i].first[0] * a + SAT_FIT[i].first[1] * b > 1 }
        val k = SAT_FIT[ch].second
        val w = LMS_TO_LINEAR_SRGB[ch]
        val s = k[0] + k[1] * a + k[2] * b + k[3] * a * a + k[4] * a * b
        val sl = slopes(a, b)
        val base = DoubleArray(3) { 1 + s * sl[it] }
        fun dot(v: DoubleArray) = w[0] * v[0] + w[1] * v[1] + w[2] * v[2]
        val f = dot(DoubleArray(3) { cube(base[it]) })
        val f1 = dot(DoubleArray(3) { 3 * sl[it] * base[it] * base[it] })
        val f2 = dot(DoubleArray(3) { 6 * sl[it] * sl[it] * base[it] })
        return s - (f * f1) / (f1 * f1 - 0.5 * f * f2)
    }

    private fun cusp(a: Double, b: Double): DoubleArray {
        val s = maxSaturation(a, b)
        val l = cbrt(1 / oklabToLinear(doubleArrayOf(1.0, s * a, s * b)).max())
        return doubleArrayOf(l, l * s)
    }

    private fun maxChroma(a: Double, b: Double, l: Double, cusp: DoubleArray): Double {
        val (cl, cc) = cusp[0] to cusp[1]
        if (l <= cl) return (cc * l) / cl
        val t = (cc * (l - 1)) / (cl - 1)
        val sl = slopes(a, b)
        val lms = DoubleArray(3) { l + t * sl[it] }
        val cubes = DoubleArray(3) { cube(lms[it]) }
        val first = DoubleArray(3) { 3 * sl[it] * lms[it] * lms[it] }
        val second = DoubleArray(3) { 6 * sl[it] * sl[it] * lms[it] }
        fun dot(r: DoubleArray, v: DoubleArray) = r[0] * v[0] + r[1] * v[1] + r[2] * v[2]
        val steps = LMS_TO_LINEAR_SRGB.map { row ->
            val f = dot(row, cubes) - 1
            val f1 = dot(row, first)
            val f2 = dot(row, second)
            val u = f1 / (f1 * f1 - 0.5 * f * f2)
            if (u >= 0) -f * u else Double.MAX_VALUE
        }
        return t + steps.min()
    }

    private fun chromaStops(l: Double, a: Double, b: Double): DoubleArray {
        val peak = cusp(a, b)
        val cMax = maxChroma(a, b, l, peak)
        val k = cMax / min(l * (peak[1] / peak[0]), (1 - l) * (peak[1] / (1 - peak[0])))
        val midS = 0.11516993 + 1 / (7.4477897 + 4.1590124 * b +
            a * (-2.19557347 + 1.75198401 * b + a * (-2.13704948 - 10.02301043 * b + a * (-4.24894561 + 5.38770819 * b + 4.69891013 * a))))
        val midT = 0.11239642 + 1 / (1.6132032 - 0.68124379 * b +
            a * (0.40370612 + 0.90148123 * b + a * (-0.27087943 + 0.6122399 * b + a * (0.00299215 - 0.45399568 * b - 0.14661872 * a))))
        val cMid = 0.9 * k * sqrt(sqrt(1 / (1 / (l * midS).pow(4) + 1 / ((1 - l) * midT).pow(4))))
        val c0 = sqrt(1 / (1 / (l * 0.4).pow(2) + 1 / ((1 - l) * 0.8).pow(2)))
        return doubleArrayOf(c0, cMid, cMax)
    }

    fun okhslToRgb(hue: Double, saturation: Double, lightness: Double): Rgb {
        val l = okhslToOklabL(lightness)
        var lab = doubleArrayOf(l, 0.0, 0.0)
        if (l > 0 && l < 1 && saturation > 0) {
            val angle = (2 * PI * (((hue % 360) + 360) % 360)) / 360
            val a = cos(angle)
            val b = sin(angle)
            val (c0, cMid, cMax) = chromaStops(l, a, b).let { Triple(it[0], it[1], it[2]) }
            val chroma = if (saturation < 0.8) {
                val t = 1.25 * saturation
                val k1 = 0.8 * c0
                (t * k1) / (1 - (1 - k1 / cMid) * t)
            } else {
                val t = 5 * (saturation - 0.8)
                val k1 = (0.2 * cMid * cMid * 1.25 * 1.25) / c0
                cMid + (t * k1) / (1 - (1 - k1 / (cMax - cMid)) * t)
            }
            lab = doubleArrayOf(l, chroma * a, chroma * b)
        }
        return linearToRgb(oklabToLinear(lab))
    }

    private fun inGamut(lin: DoubleArray) = lin.all { it >= -1e-7 && it <= 1 + 1e-7 }

    fun oklchToRgb(l: Double, c: Double, h: Double): Rgb {
        val rad = h * PI / 180
        val cs = cos(rad)
        val sn = sin(rad)
        fun at(ch: Double) = oklabToLinear(doubleArrayOf(l, ch * cs, ch * sn))
        val direct = at(c)
        if (inGamut(direct)) return linearToRgb(direct)
        var lin = at(0.0)
        var lo = 0.0
        var hi = c
        repeat(20) {
            val ch = (lo + hi) / 2
            val cand = at(ch)
            if (inGamut(cand)) { lo = ch; lin = cand } else hi = ch
        }
        return linearToRgb(lin)
    }

}
