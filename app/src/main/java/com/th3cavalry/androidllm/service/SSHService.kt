package com.th3cavalry.androidllm.service

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.Properties

/**
 * Executes commands on remote servers via SSH using JSch.
 */
class SSHService {

    /**
     * Executes a single command on a remote host and returns the combined stdout/stderr output.
     */
    suspend fun executeCommand(
        host: String,
        port: Int = 22,
        username: String,
        password: String? = null,
        privateKey: String? = null,
        command: String,
        timeoutMs: Int = 30_000
    ): String = withContext(Dispatchers.IO) {
        var session: Session? = null
        var channel: ChannelExec? = null
        try {
            val jsch = JSch()

            // Configure private key authentication if provided
            if (!privateKey.isNullOrBlank()) {
                val keyBytes = privateKey.trimIndent().toByteArray(Charsets.UTF_8)
                jsch.addIdentity("key", keyBytes, null, null)
            }

            session = jsch.getSession(username, host, port)

            if (!password.isNullOrBlank()) {
                session.setPassword(password)
            }

            val config = Properties().apply {
                put("StrictHostKeyChecking", "no")
                put("PreferredAuthentications",
                    if (!privateKey.isNullOrBlank()) "publickey,password" else "password"
                )
            }
            session.setConfig(config)
            session.connect(timeoutMs)

            channel = session.openChannel("exec") as ChannelExec
            channel.setCommand(command)

            val stdout = ByteArrayOutputStream()
            val stderr = ByteArrayOutputStream()
            channel.outputStream = stdout
            channel.setErrStream(stderr)

            channel.connect(timeoutMs)

            // Wait for the command to finish
            val deadline = System.currentTimeMillis() + timeoutMs
            while (!channel.isClosed && System.currentTimeMillis() < deadline) {
                Thread.sleep(100)
            }

            val out = stdout.toString(Charsets.UTF_8.name()).trim()
            val err = stderr.toString(Charsets.UTF_8.name()).trim()
            val exitCode = channel.exitStatus

            buildString {
                if (out.isNotEmpty()) append(out)
                if (err.isNotEmpty()) {
                    if (out.isNotEmpty()) append("\n")
                    append("[stderr] $err")
                }
                if (isEmpty()) append("(no output)")
                append("\n[exit code: $exitCode]")
            }
        } catch (e: Exception) {
            "SSH error: ${e.message}"
        } finally {
            channel?.disconnect()
            session?.disconnect()
        }
    }
}
