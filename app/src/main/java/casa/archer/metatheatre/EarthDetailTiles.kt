package casa.archer.metatheatre

import org.json.JSONObject

/** Conservative bounds of actual source triangles, including faces crossing grid edges. */
internal data class EarthDetailTile(val asset: String, val minU: Double, val maxU: Double,
    val minV: Double, val maxV: Double, val triangles: Int) {
    fun intersects(region: EarthDetailRegion): Boolean {
        val north = (90.0-region.north)/180.0
        val south = (90.0-region.south)/180.0
        if (maxV < north-MARGIN || minV > south+MARGIN) return false
        val west = ((region.west+180.0)/360.0 % 1.0 + 1.0) % 1.0
        val east = west+region.span/360.0
        // Repeating longitude matches the fragment shader, including the date line.
        return (-1..1).any { shift -> maxU+shift >= west-MARGIN && minU+shift <= east+MARGIN }
    }
    companion object { private const val MARGIN = 0.000002 }
}

internal object EarthDetailTiles {
    fun parse(json: String): List<EarthDetailTile> {
        val manifest = JSONObject(json)
        require(manifest.getInt("version") == 1)
        val entries = manifest.getJSONArray("tiles")
        return List(entries.length()) { i ->
            val tile = entries.getJSONObject(i)
            EarthDetailTile(tile.getString("asset"), tile.getDouble("minU"), tile.getDouble("maxU"),
                tile.getDouble("minV"), tile.getDouble("maxV"), tile.getInt("triangles"))
        }.also { tiles ->
            require(tiles.isNotEmpty() && tiles.map { it.asset }.distinct().size == tiles.size)
            require(tiles.sumOf { it.triangles } == manifest.getInt("triangles"))
            require(tiles.all { it.asset.matches(Regex("earth/detail/[0-9]{3}\\.glb")) &&
                it.minU in -0.05..1.05 && it.maxU in it.minU..1.05 &&
                it.minV in 0.0..1.0 && it.maxV in it.minV..1.0 && it.triangles > 0 })
        }
    }
}
