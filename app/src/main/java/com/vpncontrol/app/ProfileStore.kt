package com.vpncontrol.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class VpnProfile(val id: String, val name: String, val favorite: Boolean)

/** Multi-profile encrypted storage. active.enc remains a compatibility mirror for the tunnel service. */
object ProfileStore {
    private fun dir(context: Context) = File(context.filesDir, "profiles").apply { mkdirs() }
    private fun meta(context: Context) = File(dir(context), "index.json")
    private fun read(context: Context): MutableList<VpnProfile> {
        val f = meta(context)
        if (!f.exists()) return mutableListOf()
        return try {
            val a = JSONArray(f.readText())
            MutableList(a.length()) { i -> a.getJSONObject(i).let { VpnProfile(it.getString("id"), it.getString("name"), it.optBoolean("favorite")) } }
        } catch (_: Exception) { mutableListOf() }
    }
    private fun write(context: Context, profiles: List<VpnProfile>) {
        val a = JSONArray()
        profiles.forEach { a.put(JSONObject().put("id", it.id).put("name", it.name).put("favorite", it.favorite)) }
        meta(context).writeText(a.toString())
    }
    fun all(context: Context): List<VpnProfile> = read(context)

    /** Installs APK-bundled profiles once, encrypting each with the device Keystore key. */
    fun installBuiltIns(context: Context) {
        val bundled = listOf(
            Triple("builtin-haiku", "Haiku", "profiles/Haiku.conf"),
            Triple("builtin-tanka", "Tanka", "profiles/Tanka.conf"),
            Triple("builtin-sonnet", "Sonnet", "profiles/Sonnet.conf")
        )
        val list = read(context)
        var changed = false
        bundled.forEach { (id, name, assetPath) ->
            if (list.any { it.id == id }) return@forEach
            val plain = try {
                context.assets.open(assetPath).use { it.readBytes() }
            } catch (_: Exception) {
                return@forEach
            }
            val config = plain.toString(Charsets.UTF_8)
            if (!config.contains("[Interface]") || !config.contains("[Peer]") ||
                !config.contains("PrivateKey") || !config.contains("PublicKey") ||
                !config.contains("Endpoint")) return@forEach
            File(dir(context), "$id.enc").writeBytes(ConfigStore.encrypt(plain))
            list.add(VpnProfile(id, name, name == "Haiku"))
            changed = true
        }
        if (changed) write(context, list)
        if (activeId(context).isBlank() && list.any { it.id == "builtin-haiku" }) {
            activate(context, "builtin-haiku")
        }
    }
    fun activeId(context: Context): String = context.getSharedPreferences("vpncontrol", Context.MODE_PRIVATE).getString("activeProfileId", "") ?: ""
    fun add(context: Context, name: String, plainConfig: ByteArray): VpnProfile {
        val id = UUID.randomUUID().toString()
        File(dir(context), "$id.enc").writeBytes(ConfigStore.encrypt(plainConfig))
        val p = VpnProfile(id, name.ifBlank { "VPN-сервер ${read(context).size + 1}" }, false)
        val list = read(context); list.add(p); write(context, list); activate(context, id)
        return p
    }
    fun activate(context: Context, id: String) {
        val p = read(context).firstOrNull { it.id == id } ?: error("Профиль не найден")
        val encrypted = File(dir(context), "$id.enc").readBytes()
        File(context.filesDir, "active.enc").writeBytes(encrypted)
        context.getSharedPreferences("vpncontrol", Context.MODE_PRIVATE).edit().putString("activeProfileId", id).putString("profileName", p.name).apply()
    }
    fun toggleFavorite(context: Context, id: String) {
        val list = read(context); val i = list.indexOfFirst { it.id == id }; if (i < 0) return
        list[i] = list[i].copy(favorite = !list[i].favorite); write(context, list)
    }
    fun remove(context: Context, id: String) {
        val list = read(context); list.removeAll { it.id == id }; File(dir(context), "$id.enc").delete(); write(context, list)
        if (activeId(context) == id) {
            val next = list.firstOrNull()
            if (next != null) activate(context, next.id) else {
                File(context.filesDir, "active.enc").delete()
                context.getSharedPreferences("vpncontrol", Context.MODE_PRIVATE).edit().remove("activeProfileId").remove("profileName").apply()
            }
        }
    }
    fun exportPlainProfiles(context: Context): JSONArray {
        val out = JSONArray()
        read(context).forEach { p ->
            val encrypted = File(dir(context), "${p.id}.enc").readBytes()
            val plain = ConfigStore.decrypt(encrypted)
            out.put(JSONObject().put("name", p.name).put("favorite", p.favorite).put("config", android.util.Base64.encodeToString(plain, android.util.Base64.NO_WRAP)))
        }
        return out
    }
    fun importPlainProfiles(context: Context, profiles: JSONArray) {
        for (i in 0 until profiles.length()) {
            val o = profiles.getJSONObject(i)
            val plain = android.util.Base64.decode(o.getString("config"), android.util.Base64.DEFAULT)
            val p = add(context, o.optString("name", "Импортированный сервер"), plain)
            if (o.optBoolean("favorite")) toggleFavorite(context, p.id)
        }
    }
}
