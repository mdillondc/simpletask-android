/**
 * This file is part of Todo.txt Touch, an Android app for managing your todo.txt file (http://todotxt.com).

 * Copyright (c) 2009-2012 Todo.txt contributors (http://todotxt.com)

 * LICENSE:

 * Todo.txt Touch is free software: you can redistribute it and/or modify it under the terms of the GNU General Public
 * License as published by the Free Software Foundation, either version 2 of the License, or (at your option) any
 * later version.

 * Todo.txt Touch is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License for more
 * details.

 * You should have received a copy of the GNU General Public License along with Todo.txt Touch.  If not, see
 * //www.gnu.org/licenses/>.

 * @author Todo.txt contributors @yahoogroups.com>
 * *
 * @license http://www.gnu.org/licenses/gpl.html
 * *
 * @copyright 2009-2012 Todo.txt contributors (http://todotxt.com)
 */
package nl.mpcjanssen.simpletask.remote

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.core.app.ActivityCompat
import nl.mpcjanssen.simpletask.Simpletask
import nl.mpcjanssen.simpletask.ThemedNoActionBarActivity
import nl.mpcjanssen.simpletask.TodoApplication
import nl.mpcjanssen.simpletask.databinding.LoginBinding
import nl.mpcjanssen.simpletask.util.showToastLong

class LoginScreen : ThemedNoActionBarActivity() {
    private lateinit var binding: LoginBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (FileStore.isAuthenticated) {
            switchToTodolist()
        }

        setTheme(TodoApplication.config.activeTheme)

        binding = LoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val loginButton = binding.login
        loginButton.setOnClickListener {
            startLogin()
        }
    }

    private fun switchToTodolist() {
        val intent = Intent(this, Simpletask::class.java)
        startActivity(intent)
        finish()
    }

    private fun finishLogin() {
        if (FileStore.isAuthenticated) {
            switchToTodolist()
        } else {
            showToastLong(this, "Storage access denied")
        }
    }

// SAF-only: legacy continueLogin removed

    internal fun startLogin() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
            )
        }
        startActivityForResult(intent, REQUEST_PICK_FOLDER)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQUEST_PICK_FOLDER && resultCode == android.app.Activity.RESULT_OK) {
            val uri: Uri? = data?.data
            if (uri != null) {
                val takeFlags = (data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION))
                contentResolver.takePersistableUriPermission(uri, takeFlags)
                TodoApplication.config.safTreeUriString = uri.toString()
                finishLogin()
                return
            }
        }
        showToastLong(this, "No folder selected")
    }

    companion object {
        private const val REQUEST_PICK_FOLDER = 100
        internal val TAG = LoginScreen::class.java.simpleName
    }
}