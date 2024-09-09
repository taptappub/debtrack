package io.tappatap.debtrack

import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

class JiraBacklogPlugin implements Plugin<Project> {
    @Override
    void apply(Project project) {
        project.extensions.create("jiraConfig", JiraConfig)

        project.task('Debtrack') {
            group = "verification"
            description = "Finds all comments starting with BACKLOG, creates Jira issues, and appends the issue link to the comment"

            doLast {
                def jiraConfig = project.jiraConfig
                if (!jiraConfig.baseUrl || !jiraConfig.projectKey || !jiraConfig.username || !jiraConfig.apiToken) {
                    throw new GradleException("Jira configuration is missing. Please provide all required fields.")
                }

                def sourceDirs = ["src/main/java"/*, "src/main/kotlin"*/]

                sourceDirs.each { dir ->
                    def dirFile = project.file(dir)
                    if (dirFile.exists()) {
                        dirFile.eachFileRecurse { file ->
                            if (file.name.endsWith(".java") || file.name.endsWith(".kt")) {
                                def lines = file.readLines()
                                def modified = false
                                lines.eachWithIndex { line, index ->
                                    if (line.trim().startsWith("//BACKLOG") && !line.contains("Jira:")) {
                                        def comment = line.trim().substring(2).trim()
                                        // Извлечение текста комментария

                                        println "BACKLOG comment found in ${file} at line ${index + 1}:"
                                        println line.trim()
                                        println "-" * 80

                                        // Создаем задачу в Jira и получаем ссылку на нее
                                        def issueUrl = createJiraIssue(jiraConfig, file, index + 1, comment)
                                        if (issueUrl) {
                                            println "Jira issue created: ${issueUrl}"

                                            lines[index] = "${line} //Jira: ${issueUrl}"
                                            modified = true
                                        }
                                    }
                                }

                                // Перезаписываем файл, если были изменения
                                if (modified) {
                                    file.text = lines.join(System.lineSeparator())
                                    println "File ${file.name} updated with Jira issue link."
                                }
                            }
                        }
                    } else {
                        println "Directory $dir does not exist"
                    }
                }
            }
        }
    }

    def createJiraIssue(JiraConfig jiraConfig, file, lineNumber, comment) {
        def jiraUrl = "${jiraConfig.baseUrl}/rest/api/2/issue"
        def authString = "${jiraConfig.username}:${jiraConfig.apiToken}".getBytes().encodeBase64().toString()

        def issueData = [
                fields: [
                        project    : [key: jiraConfig.projectKey],
                        summary    : "[BACKLOG] $comment in ${file.name}",
                        description: "${file.name} at line ${lineNumber}.\n\nComment:\n${comment}",
                        issuetype  : [name: "Task"]
                ]
        ]

        def jsonBody = JsonOutput.toJson(issueData)
        def url = new URL(jiraUrl)
        def connection = (HttpURLConnection) url.openConnection()

        try {
            connection.with {
                doOutput = true
                requestMethod = 'POST'
                setRequestProperty("Authorization", "Basic ${authString}")
                setRequestProperty("Content-Type", "application/json")

                outputStream.withWriter("UTF-8") { writer ->
                    writer << jsonBody
                }

                def responseCode = connection.responseCode
                if (responseCode == 201) {
                    def jsonResponse = new JsonSlurper().parseText(connection.inputStream.text)
                    def issueKey = jsonResponse.key
                    return "${jiraConfig.baseUrl}/browse/${issueKey}"
                } else {
                    println "Failed to create Jira issue: ${responseCode}"
                    connection.inputStream.withReader("UTF-8") { reader ->
                        println reader.text
                    }
                    return null
                }
            }
        } catch (Exception e) {
            println "Error occurred while creating Jira issue: ${e.message}"
            return null
        } finally {
            if (connection != null) {
                connection.disconnect()
            }
        }
    }

    class JiraConfig {
        String baseUrl
        String projectKey
        String username
        String apiToken
    }
}