package casa.archer.metatheatre

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.acos
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Authored Earth surface. Vertex positions, normals and UVs are the exported
 * values; a geographic submission may reference a subset but does not move them.
 */
internal class EarthSurfaceGeometry(
    val positions: FloatArray,
    val normals: FloatArray,
    val uvs: FloatArray,
    val indices: IntArray,
) {
    val vertexCount = positions.size / 3
    val triangleCount = indices.size / 3
    val maxEdgeAngle: Float
    /** Extra cap past the horizon so silhouette triangles and stereo eyes stay covered. */
    val marginRadians: Float

    init {
        require(positions.size % 3 == 0 && normals.size == positions.size)
        require(uvs.size == vertexCount * 2 && indices.size % 3 == 0 && vertexCount > 0 && triangleCount > 0)
        maxEdgeAngle = measureMaxEdge()
        // Two edge-lengths cover a triangle that straddles the horizon. The floor is head motion, not a zoom gate.
        marginRadians = max(maxEdgeAngle * 2f, Math.toRadians(2.0).toFloat())
    }

    fun direction(vertex: Int): FloatArray {
        val x = positions[vertex * 3]
        val y = positions[vertex * 3 + 1]
        val z = positions[vertex * 3 + 2]
        val length = sqrt(x * x + y * y + z * z)
        require(length > 1e-8f) { "Surface vertex $vertex is degenerate" }
        return floatArrayOf(x / length, y / length, z / length)
    }

    private fun measureMaxEdge(): Float {
        var widest = 0f
        for (triangle in 0 until triangleCount) {
            val a = direction(indices[triangle * 3])
            val b = direction(indices[triangle * 3 + 1])
            val c = direction(indices[triangle * 3 + 2])
            widest = max(widest, max(sphericalAngle(a, b), max(sphericalAngle(b, c), sphericalAngle(c, a))))
        }
        return widest
    }

    companion object {
        /** Packaged `earth_dynamic_surface.glb`. The 260,096-triangle detail sphere is not in this checkout. */
        const val AUTHORED_TRIANGLES = 16_128

        fun parseGlb(bytes: ByteArray): EarthSurfaceGeometry {
            require(bytes.size > 28) { "Earth surface file is not a GLB" }
            val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            require(header.int == 0x46546C67 && header.int == 2) { "Earth surface file is not a GLB 2 mesh" }
            val length = header.int
            require(length in 28..bytes.size)
            val jsonLength = header.int
            require(header.int == 0x4E4F534A && jsonLength > 0 && header.position() + jsonLength + 8 <= bytes.size)
            val jsonBytes = ByteArray(jsonLength)
            header.get(jsonBytes)
            val json = GlbJson.parse(String(jsonBytes, Charsets.UTF_8).trim { it <= ' ' || it == '\u0000' })
            val binLength = header.int
            require(header.int == 0x004E4942 && binLength > 0 && header.position() + binLength <= bytes.size)
            val binStart = header.position()
            val primitive = json.array("meshes").obj(0).array("primitives").obj(0)
            val attributes = primitive.obj("attributes")
            return EarthSurfaceGeometry(
                readFloats(json, bytes, binStart, attributes.integer("POSITION"), 3),
                readFloats(json, bytes, binStart, attributes.integer("NORMAL"), 3),
                readFloats(json, bytes, binStart, attributes.integer("TEXCOORD_0"), 2),
                readIndices(json, bytes, binStart, primitive.integer("indices")),
            )
        }

        private fun readFloats(json: GlbJson.Object, bytes: ByteArray, binStart: Int, accessor: Int, components: Int): FloatArray {
            val acc = json.array("accessors").obj(accessor)
            require(acc.integer("componentType") == 5126)
            require(acc.text("type") == if (components == 2) "VEC2" else "VEC3")
            val count = acc.integer("count")
            val view = json.array("bufferViews").obj(acc.integer("bufferView"))
            val stride = view.optionalInt("byteStride") ?: components * 4
            val start = binStart + (view.optionalInt("byteOffset") ?: 0) + (acc.optionalInt("byteOffset") ?: 0)
            val out = FloatArray(count * components)
            val bin = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            for (vertex in 0 until count) {
                val base = start + vertex * stride
                for (component in 0 until components) out[vertex * components + component] = bin.getFloat(base + component * 4)
            }
            return out
        }

        private fun readIndices(json: GlbJson.Object, bytes: ByteArray, binStart: Int, accessor: Int): IntArray {
            val acc = json.array("accessors").obj(accessor)
            require(acc.text("type") == "SCALAR")
            val count = acc.integer("count")
            val view = json.array("bufferViews").obj(acc.integer("bufferView"))
            val component = acc.integer("componentType")
            val width = when (component) { 5121 -> 1; 5123 -> 2; 5125 -> 4; else -> error("Unsupported index type $component") }
            val stride = view.optionalInt("byteStride") ?: width
            val start = binStart + (view.optionalInt("byteOffset") ?: 0) + (acc.optionalInt("byteOffset") ?: 0)
            val out = IntArray(count)
            val bin = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            for (index in 0 until count) {
                val base = start + index * stride
                out[index] = when (component) {
                    5121 -> bin.get(base).toInt() and 0xFF
                    5123 -> bin.getShort(base).toInt() and 0xFFFF
                    else -> bin.getInt(base)
                }
            }
            return out
        }
    }
}

/** The Android unit-test stub of org.json throws, so GLB metadata is read here. */
private object GlbJson {
    class Object(private val values: Map<String, Any?>) {
        fun array(name: String) = values.getValue(name) as Array
        fun obj(name: String) = values.getValue(name) as Object
        fun text(name: String) = values.getValue(name) as String
        fun integer(name: String) = (values.getValue(name) as Number).toInt()
        fun optionalInt(name: String) = (values[name] as Number?)?.toInt()
    }

    class Array(private val values: List<Any?>) {
        fun obj(index: Int) = values[index] as Object
    }

    fun parse(text: String): Object {
        val parser = Parser(text)
        val value = parser.parse()
        require(value is Object) { "Earth GLB JSON must be an object" }
        return value
    }

    private class Parser(val text: String) {
        var index = 0
        fun parse(): Any? {
            skip()
            return when (text[index]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> literal(4, true)
                'f' -> literal(5, false)
                'n' -> literal(4, null)
                else -> parseNumber()
            }
        }

        private fun parseObject(): Object {
            index++
            val values = LinkedHashMap<String, Any?>()
            skip()
            if (text[index] == '}') { index++; return Object(values) }
            while (true) {
                skip()
                val key = parseString()
                skip()
                require(text[index] == ':')
                index++
                values[key] = parse()
                skip()
                when (text[index]) {
                    ',' -> index++
                    '}' -> { index++; return Object(values) }
                    else -> error("Invalid Earth GLB JSON")
                }
            }
        }

        private fun parseArray(): Array {
            index++
            val values = ArrayList<Any?>()
            skip()
            if (text[index] == ']') { index++; return Array(values) }
            while (true) {
                values.add(parse())
                skip()
                when (text[index]) {
                    ',' -> index++
                    ']' -> { index++; return Array(values) }
                    else -> error("Invalid Earth GLB JSON")
                }
            }
        }

        private fun parseString(): String {
            index++
            val out = StringBuilder()
            while (text[index] != '"') {
                val char = text[index++]
                out.append(if (char != '\\') char else when (val escaped = text[index++]) {
                    '"', '\\', '/' -> escaped
                    'b' -> '\b'
                    'f' -> '\u000c'
                    'n' -> '\n'
                    'r' -> '\r'
                    't' -> '\t'
                    'u' -> text.substring(index, index + 4).toInt(16).toChar().also { index += 4 }
                    else -> error("Invalid Earth GLB string")
                })
            }
            index++
            return out.toString()
        }

        private fun parseNumber(): Number {
            val start = index
            if (text[index] == '-') index++
            while (index < text.length && text[index].isDigit()) index++
            var fractional = false
            if (index < text.length && text[index] == '.') {
                fractional = true
                index++
                while (index < text.length && text[index].isDigit()) index++
            }
            if (index < text.length && (text[index] == 'e' || text[index] == 'E')) {
                fractional = true
                index++
                if (text[index] == '+' || text[index] == '-') index++
                while (text[index].isDigit()) index++
            }
            val token = text.substring(start, index)
            return if (fractional) token.toDouble() else token.toLong()
        }

        private fun literal(length: Int, value: Any?): Any? {
            index += length
            return value
        }

        private fun skip() {
            while (index < text.length && text[index].isWhitespace()) index++
        }
    }
}

internal fun sphericalAngle(a: FloatArray, b: FloatArray): Float {
    val dot = (a[0] * b[0] + a[1] * b[1] + a[2] * b[2]).coerceIn(-1f, 1f)
    return acos(dot)
}

internal fun FloatArray.asUnitDirection(): FloatArray {
    val length = sqrt(this[0] * this[0] + this[1] * this[1] + this[2] * this[2])
    require(length > 1e-8f && size == 3)
    return floatArrayOf(this[0] / length, this[1] / length, this[2] / length)
}
