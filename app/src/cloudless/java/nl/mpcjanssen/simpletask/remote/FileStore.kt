package nl.mpcjanssen.simpletask.remote

import android.os.*
import android.util.Log
import android.content.ContentResolver
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.BufferedReader
import java.io.InputStreamReader
import nl.mpcjanssen.simpletask.R
import nl.mpcjanssen.simpletask.TodoApplication
import nl.mpcjanssen.simpletask.util.broadcastAuthFailed
import nl.mpcjanssen.simpletask.util.join
import java.io.File
import java.io.FilenameFilter
import java.util.*
import kotlin.reflect.KClass

object FileStore : IFileStore {
    private const val TAG = "FileStore"

    private var lastSeenRemoteId by TodoApplication.config.StringOrNullPreference(R.string.file_current_version_id)
    private var observer: TodoObserver? = null

    override val isOnline = true

    init {
        Log.i(TAG, "onCreate")
        Log.i(TAG, "Default path: ${getDefaultFile().path}")
        observer = null
    }

    override val isEncrypted: Boolean
        get() = false

    val isAuthenticated: Boolean
        get() {
            return if (TodoApplication.config.useSaf) {
                TodoApplication.config.safTreeUri != null
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Environment.isExternalStorageManager()
                } else {
                    true
                }
            }
        }

    override fun loadTasksFromFile(file: File): List<String> {
        if (!isAuthenticated) {
            broadcastAuthFailed(TodoApplication.app.localBroadCastManager)
            return emptyList()
        }
        return if (TodoApplication.config.useSaf) {
            val df = ensureTodoDoc()
            if (df == null) {
                Log.w(TAG, "SAF: todo.txt not found and could not be created")
                emptyList()
            } else {
                Log.i(TAG, "SAF: Loading tasks from ${df.uri}")
                val text = readDocumentFile(df)
                val result = if (text.isEmpty()) emptyList() else text.split("\n")
                lastSeenRemoteId = df.lastModified().toString()
                result
            }
        } else {
            Log.i(TAG, "Loading tasks")
            val lines = file.readLines()
            Log.i(TAG, "Read ${lines.size} lines from $file")
            setWatching(file)
            lastSeenRemoteId = file.lastModified().toString()
            lines
        }
    }

    override fun needSync(file: File): Boolean {
        if (!isAuthenticated) {
            broadcastAuthFailed(TodoApplication.app.localBroadCastManager)
            return true
        }
        return if (TodoApplication.config.useSaf) {
            val df = getTodoDoc()
            val lm = df?.lastModified()?.toString()
            lastSeenRemoteId != lm
        } else {
            lastSeenRemoteId != file.lastModified().toString()
        }
    }

    override fun todoNameChanged() {
        lastSeenRemoteId = ""
    }

    override fun writeFile(file: File, contents: String) {
        if (!isAuthenticated) {
            broadcastAuthFailed(TodoApplication.app.localBroadCastManager)
            return
        }
        if (TodoApplication.config.useSaf) {
            val df = ensureTodoDoc()
            if (df == null) {
                Log.w(TAG, "SAF: Cannot write, todo.txt not available")
                return
            }
            Log.i(TAG, "SAF: Writing todo to ${df.uri}")
            writeDocumentFile(df, contents, append = false)
        } else {
            Log.i(TAG, "Writing file to  ${file.canonicalPath}")
            file.writeText(contents)
        }
    }

    override fun readFile(file: File, fileRead: (contents: String) -> Unit) {
        if (!isAuthenticated) {
            broadcastAuthFailed(TodoApplication.app.localBroadCastManager)
            return
        }
        if (TodoApplication.config.useSaf) {
            val df = ensureTodoDoc()
            val contents = if (df == null) "" else readDocumentFile(df)
            fileRead(contents)
        } else {
            Log.i(TAG, "Reading file: ${file.path}")
            val lines = file.readLines()
            val contents = join(lines, "\n")
            fileRead(contents)
        }
    }

    override fun loginActivity(): KClass<*>? {
        return LoginScreen::class
    }

    // SAF helpers and utilities
    private fun useSaf(): Boolean = TodoApplication.config.useSaf

    private fun getTreeUri(): Uri? = TodoApplication.config.safTreeUri

    private fun getTree(): DocumentFile? {
        val uri = getTreeUri() ?: return null
        return DocumentFile.fromTreeUri(TodoApplication.app, uri)
    }

    private fun getChildByName(parent: DocumentFile, name: String, mimePrefix: String? = null): DocumentFile? {
        parent.listFiles().forEach { doc ->
            if (doc.name == name) {
                if (mimePrefix == null || (doc.type?.startsWith(mimePrefix) == true)) {
                    return doc
                }
            }
        }
        return null
    }

    private fun ensureFile(parent: DocumentFile, name: String, mime: String): DocumentFile? {
        val existing = getChildByName(parent, name)
        if (existing != null) return existing
        return parent.createFile(mime, name)
    }

    private fun getTodoDoc(): DocumentFile? {
        val tree = getTree() ?: return null
        return getChildByName(tree, TodoApplication.config.todoFilename)
    }

    private fun ensureTodoDoc(): DocumentFile? {
        val tree = getTree() ?: return null
        return ensureFile(tree, TodoApplication.config.todoFilename, "text/plain")
    }

    private fun getDoneDoc(): DocumentFile? {
        val tree = getTree() ?: return null
        return getChildByName(tree, TodoApplication.config.doneFilename)
    }

    private fun ensureDoneDoc(): DocumentFile? {
        val tree = getTree() ?: return null
        return ensureFile(tree, TodoApplication.config.doneFilename, "text/plain")
    }

    private fun readDocumentFile(df: DocumentFile): String {
        return try {
            TodoApplication.app.contentResolver.openInputStream(df.uri).use { input ->
                if (input == null) return ""
                InputStreamReader(input).use { isr ->
                    BufferedReader(isr).use { br ->
                        br.readText()
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "SAF: read failed ${df.uri}", t)
            ""
        }
    }

    private fun writeDocumentFile(df: DocumentFile, contents: String, append: Boolean) {
        try {
            val mode = if (append) "wa" else "rwt"
            TodoApplication.app.contentResolver.openOutputStream(df.uri, mode).use { output ->
                if (output != null) {
                    output.write(contents.toByteArray())
                    output.flush()
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "SAF: write failed ${df.uri}", t)
        }
    }

    private fun setWatching(file: File) {
        Log.i(TAG, "Observer: adding folder watcher on ${file.parent}")

        val obs = observer
        if (obs != null && file.canonicalPath == obs.fileName) {
            Log.w(TAG, "Observer: already watching: ${file.canonicalPath}")
            return
        } else if (obs != null) {
            Log.w(TAG, "Observer: already watching different path: ${obs.fileName}")
            obs.ignoreEvents(true)
            obs.stopWatching()
            observer = null
        }

        observer = TodoObserver(file)
        Log.i(TAG, "Observer: modifying done")
    }

    override fun saveTasksToFile(file: File, lines: List<String>, eol: String): File {
        if (!isAuthenticated) {
            broadcastAuthFailed(TodoApplication.app.localBroadCastManager)
            return file
        }
        return if (TodoApplication.config.useSaf) {
            val df = ensureTodoDoc()
            if (df == null) {
                Log.w(TAG, "SAF: Cannot save, todo.txt not available")
                file
            } else {
                Log.i(TAG, "SAF: Saving tasks to ${df.uri}")
                writeDocumentFile(df, lines.joinToString(eol) + eol, append = false)
                lastSeenRemoteId = df.lastModified().toString()
                file
            }
        } else {
            Log.i(TAG, "Saving tasks to file: ${file.path}")
            val obs = observer
            obs?.ignoreEvents(true)
            writeFile(file, lines.joinToString(eol) + eol)
            obs?.delayedStartListen(1000)
            lastSeenRemoteId = file.lastModified().toString()
            file
        }
    }

    override fun appendTaskToFile(file: File, lines: List<String>, eol: String) {
        if (!isAuthenticated) {
            broadcastAuthFailed(TodoApplication.app.localBroadCastManager)
            return
        }
        if (TodoApplication.config.useSaf) {
            val df = ensureDoneDoc()
            if (df == null) {
                Log.w(TAG, "SAF: Cannot append, done.txt not available")
                return
            }
            Log.i(TAG, "SAF: Appending ${lines.size} tasks to ${df.uri}")
            writeDocumentFile(df, lines.joinToString(eol) + eol, append = true)
        } else {
            Log.i(TAG, "Appending ${lines.size} tasks to ${file.path}")
            file.appendText(lines.joinToString(eol) + eol)
        }
    }

    override fun logout() {
    }

    override fun getDefaultFile(): File {
        return File(TodoApplication.app.getExternalFilesDir(null), "todo.txt")
    }

    override fun loadFileList(file: File, txtOnly: Boolean): List<FileEntry> {
        if (!isAuthenticated) {
            broadcastAuthFailed(TodoApplication.app.localBroadCastManager)
            return emptyList()
        }
        if (TodoApplication.config.useSaf) {
            // File browsing is not used with SAF; return empty list to disable legacy dialog.
            Log.i(TAG, "SAF: loadFileList unused")
            return emptyList()
        }

        val result = ArrayList<FileEntry>()

        if (file.canonicalPath == "/") {
            TodoApplication.app.getExternalFilesDir(null)?.let {
                result.add(FileEntry(it, true))
            }
        }

        val filter = FilenameFilter { dir, filename ->
            val sel = File(dir, filename)
            if (!sel.canRead()) false
            else {
                if (sel.isDirectory) {
                    result.add(FileEntry(File(filename), true))
                } else if (!txtOnly || filename.lowercase().endsWith(".txt")) {
                    result.add(FileEntry(File(filename), false))
                }
                true
            }
        }

        // Run the file applyFilter for side effects
        file.list(filter)

        return result
    }

    class TodoObserver(val file: File) : FileObserver(file) {
        private val tag = "FileWatchService"
        val fileName: String = file.canonicalPath
        private var ignoreEvents: Boolean = false
        private val handler: Handler

        private val delayedEnable = Runnable {
            Log.i(tag, "Observer: Delayed enabling events for: $fileName ")
            ignoreEvents(false)
        }

        init {
            this.startWatching()

            Log.i(tag, "Observer: creating observer on: $fileName")
            this.ignoreEvents = false
            this.handler = Handler(Looper.getMainLooper())
        }

        fun ignoreEvents(ignore: Boolean) {
            Log.i(tag, "Observer: observing events on $fileName? ignoreEvents: $ignore")
            this.ignoreEvents = ignore
        }

        override fun onEvent(event: Int, eventPath: String?) {
            if (eventPath != null && eventPath == fileName) {
                Log.d(tag, "Observer event: $fileName:$event")
                if (event == CLOSE_WRITE || event == MODIFY || event == MOVED_TO) {
                    if (ignoreEvents) {
                        Log.i(tag, "Observer: ignored event on: $fileName")
                    } else {
                        Log.i(tag, "File changed {}$fileName")
                        remoteTodoFileChanged()
                    }
                }
            }
        }

        fun delayedStartListen(ms: Int) {
            // Cancel any running timers
            handler.removeCallbacks(delayedEnable)

            // Reschedule
            Log.i(tag, "Observer: Adding delayed enabling to todoQueue")
            handler.postDelayed(delayedEnable, ms.toLong())
        }
    }
}