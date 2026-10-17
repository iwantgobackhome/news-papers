package app.fractal.sync

import app.fractal.data.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.mockito.Mockito.*
import org.testng.Assert.*
import org.testng.annotations.Test
import java.lang.reflect.Proxy
import java.util.concurrent.Executor

class MetadataDrainTest {
    private fun json(value: String) = WireJson.format.parseToJsonElement(value).jsonObject
    private fun mutation(id: Int, key: String = "p", patch: String, base: String = "{\"rev\":0}") =
        MetadataMutation("request-$id", "paper", key, json(base).revision(), base, patch, "device", id.toLong())

    private class Store {
        val queue = mutableListOf<MetadataMutation>()
        val papers = mutableMapOf<String, LibraryEntity>()
        val folders = mutableMapOf<String, FolderEntity>()
        val history = mutableMapOf<String, HistoryEntity>()
        val authorities = mutableMapOf<String, MetadataAuthority>()
        val database = mock(FractalDatabase::class.java)
        private inline fun <reified T> dao(crossinline call: (String, Array<out Any?>) -> Any?): T =
            Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
                call(method.name, args ?: emptyArray())
            } as T
        init {
            `when`(database.suspendingTransactionId).thenReturn(ThreadLocal<Int>())
            `when`(database.transactionExecutor).thenReturn(Executor { task -> Thread(task).apply { isDaemon = true }.start() })
            `when`(database.metadata()).thenReturn(dao<MetadataDao> { name, args -> when (name) {
                "pending" -> queue.filter { args.size == 1 || it.kind == args[0] && it.entityId == args[1] }.sortedBy { it.createdAt }
                "enqueue" -> { queue.add(args[0] as MetadataMutation); Unit }
                "acknowledge" -> { queue.removeAll { it.requestId == args[0] }; Unit }
                "folders" -> folders.values.toList()
                "folder" -> folders[args[0]]
                "history" -> history[args[0]]
                "historyEntries" -> history.values.filter { it.paperKey == args[0] }
                "authority" -> authorities[args[0]]
                "upsert" -> { when (val row = args[0]) {
                    is MetadataAuthority -> authorities[row.key] = row
                    is FolderEntity -> folders[row.id] = row
                    is HistoryEntity -> history[row.id] = row
                }; Unit }
                else -> error("Metadata DAO: $name")
            } })
            `when`(database.library()).thenReturn(dao<LibraryDao> { name, args -> when (name) {
                "all" -> papers.values.toList()
                "get" -> papers[args[0]]
                "upsert" -> { val row = args[0] as LibraryEntity; papers[row.paperKey] = row; Unit }
                else -> error("Library DAO: $name")
            } })
            `when`(database.annotations()).thenReturn(dao<AnnotationDao> { name, _ ->
                check(name == "dirty"); emptyList<AnnotationEntity>()
            })
            `when`(database.syncState()).thenReturn(dao<SyncStateDao> { name, _ -> when (name) {
                "cursor" -> "0"
                "upsert" -> Unit
                else -> error(name)
            } })
        }
    }

    private inner class Hub : HubDataClient {
        val records = mutableMapOf<String, JsonObject>()
        var pushes = 0
        var conflicts = 0
        var alwaysConflict = false
        override suspend fun data(path: String, method: String, body: JsonElement?): JsonElement {
            if (path.startsWith("/api/sync/pull")) return json("{}")
            pushes++
            val results = body!!.jsonObject.getValue("papers").jsonArray.map { value ->
                val envelope = value.jsonObject
                val key = envelope.text("paperKey")!!
                val current = records.getOrPut(key) { paper(key) }
                val applied = !alwaysConflict && envelope.text("baseRev")!!.toInt() == current.revision()
                if (!applied) conflicts++
                val next = if (applied) JsonObject(current + envelope.getValue("patch").jsonObject +
                    ("rev" to JsonPrimitive(current.revision() + 1))) else current
                records[key] = next
                buildJsonObject {
                    put("kind", "paper"); put("id", key); put("applied", applied); put("conflict", !applied); put("current", next)
                }
            }
            return buildJsonObject { put("metadataResults", JsonArray(results)) }
        }
    }
    private fun paper(key: String) = json("""{"id":"$key","bibtexKey":"$key","paperKey":"$key","title":"Title","authors":[],"addedAt":"2026-01-01","updatedAt":"2026-01-01","status":"ready","rev":0}""")

    @Test fun legacyReadQueueAcrossFourPapersDrainsInOneCall() = runBlocking {
        val store = Store()
        repeat(30) { index -> store.queue += mutation(index, "p${index % 4}", """{"lastReadAt":"2026-10-17T00:00:${index.toString().padStart(2, '0')}Z","readProgress":{"page":${index + 1}}}""") }
        val hub = Hub()
        SyncEngine(store.database, hub).syncOnce()
        assertTrue(store.queue.isEmpty())
        assertEquals(hub.pushes, 1)
        assertEquals(hub.conflicts, 0)
        assertEquals(hub.records["p1"]!!["readProgress"]!!.jsonObject.text("page"), "30")
    }

    @Test fun mergedConflictReplacesWholeGroupAndStopsAtPassBound() = runBlocking {
        val store = Store()
        store.queue += mutation(1, patch = """{"tags":["keep","add"]}""", base = """{"rev":0,"tags":["old","keep"]}""")
        store.queue += mutation(2, patch = """{"tags":["keep"]}""", base = """{"rev":0,"tags":["keep","add"]}""")
        val hub = Hub().also {
            it.alwaysConflict = true
            it.records["p"] = JsonObject(paper("p") + json("""{"rev":2,"tags":["old","keep","remote"]}"""))
        }
        SyncEngine(store.database, hub).syncOnce()
        assertEquals(store.queue.size, 1)
        assertEquals(hub.conflicts, 10)
        assertEquals(json(store.queue.single().patchJson)["tags"], json("""{"tags":["keep","remote"]}""")["tags"])
        assertFalse(store.queue.single().requestId in listOf("request-1", "request-2"))
    }

    @Test fun enqueueTwoReadsKeepsFirstBaseAndLatestIntent() = runBlocking {
        val store = Store()
        store.papers["p"] = libraryEntity(paper("p"), null, false)
        val metadata = MetadataStore(store.database) { "device" }
        metadata.read("p", 1)
        val first = store.queue.single()
        metadata.read("p", 8)
        val merged = store.queue.single()
        assertEquals(merged.baseJson, first.baseJson)
        assertEquals(merged.createdAt, first.createdAt)
        assertNotEquals(merged.requestId, first.requestId)
        assertEquals(json(merged.patchJson)["readProgress"]!!.jsonObject.text("page"), "8")
    }

    @Test fun editDuringConflictingPushRetainsLatestIntent() = runBlocking {
        val store = Store()
        store.papers["p"] = libraryEntity(paper("p"), null, false)
        val metadata = MetadataStore(store.database) { "device" }
        metadata.save("p", false)
        val hub = Hub().also {
            it.records["p"] = JsonObject(paper("p") + ("rev" to JsonPrimitive(2)))
        }
        val client = object : HubDataClient {
            override suspend fun data(path: String, method: String, body: JsonElement?): JsonElement {
                if (path.startsWith("/api/sync/push")) metadata.save("p", true)
                return hub.data(path, method, body)
            }
        }
        SyncEngine(store.database, client).syncOnce()
        assertEquals(store.queue.size, 1)
        assertEquals(store.queue.single().baseRev, 2)
        assertEquals(json(store.queue.single().patchJson).text("saved"), "true")
        SyncEngine(store.database, hub).syncOnce()
        assertTrue(store.queue.isEmpty())
        assertEquals(hub.records["p"]!!.text("saved"), "true")
    }

    @Test fun enqueueRenameDeleteAndHistoryPlacementKeepLatestIntent() = runBlocking {
        val store = Store()
        val metadata = MetadataStore(store.database) { "device" }
        metadata.folder("Original", null, "folder")
        metadata.folder("Renamed", null, "folder")
        metadata.deleteFolder("folder")
        val folder = store.queue.single()
        assertEquals(folder.kind, "folder")
        assertEquals(json(folder.patchJson).text("name"), "Renamed")
        assertEquals(json(folder.patchJson).text("deleted"), "true")
        assertEquals(folder.baseRev, 0)
        store.history["answer"] = historyEntity(json("""{"id":"answer","status":"completed","rev":4}"""))
        metadata.placeAnswer("answer", AnswerPlacement(1, 0.1f, 0.2f, updatedAt = "2026-10-17T00:00:00Z"))
        metadata.placeAnswer("answer", AnswerPlacement(3, 0.5f, 0.6f, updatedAt = "2026-10-17T00:01:00Z"))
        val history = store.queue.single { it.kind == "history" }
        assertEquals(history.baseRev, 4)
        assertEquals(json(history.patchJson)["placement"]!!.jsonObject.text("page"), "3")
        assertEquals(historyMutationEntry(history)["placement"], json(history.patchJson)["placement"])
    }

    @Test fun dependentFolderDeletionDrainsAfterPaperEdit() = runBlocking {
        val store = Store()
        store.queue += mutation(1, patch = """{"saved":true}""")
        store.queue += mutation(2, key = "folder", patch = """{"deleted":true}""", base = """{"id":"folder","rev":0}""").copy(kind = "folder")
        val hub = Hub()
        val calls = mutableListOf<JsonObject>()
        val client = object : HubDataClient {
            override suspend fun data(path: String, method: String, body: JsonElement?): JsonElement {
                if (!path.startsWith("/api/sync/push")) return hub.data(path, method, body)
                calls += body!!.jsonObject
                val response = hub.data(path, method, body).jsonObject
                val folderResults = body.jsonObject.getValue("folders").jsonArray.map { folder -> buildJsonObject {
                    put("kind", "folder"); put("id", folder.jsonObject.getValue("id")); put("applied", true)
                    put("current", json("""{"id":"folder","rev":1,"deleted":true}"""))
                } }
                return JsonObject(response + ("metadataResults" to JsonArray(response.getValue("metadataResults").jsonArray + folderResults)))
            }
        }
        SyncEngine(store.database, client).syncOnce()
        assertTrue(store.queue.isEmpty())
        assertEquals(calls.size, 2)
        assertTrue(calls.first().getValue("folders").jsonArray.isEmpty())
        assertEquals(calls.last().getValue("folders").jsonArray.size, 1)
    }

    @Test fun absoluteFieldsUseLatestEditAgainstOriginalBase() {
        val first = mutation(1, patch = """{"tags":["old","new"],"collections":["a","b"],"saved":false,"name":"Renamed","placement":{"page":1}}""", base = """{"rev":3,"tags":["old"],"saved":true}""")
        val last = mutation(2, patch = """{"tags":[],"collections":["b"],"saved":true,"deleted":true,"placement":{"page":9}}""")
        val merged = coalesceMetadataMutations(listOf(first, last))
        assertEquals(merged.baseRev, 3)
        assertEquals(merged.baseJson, first.baseJson)
        assertEquals(merged.requestId, last.requestId)
        val patch = json(merged.patchJson)
        assertEquals(patch, json("""{"tags":[],"collections":["b"],"saved":true,"name":"Renamed","deleted":true,"placement":{"page":9}}"""))
        assertEquals(MetadataMerge.apply(json("""{"tags":["old","remote"]}"""), json(merged.baseJson), patch)["tags"], json("""{"tags":["remote"]}""")["tags"])
    }
}
