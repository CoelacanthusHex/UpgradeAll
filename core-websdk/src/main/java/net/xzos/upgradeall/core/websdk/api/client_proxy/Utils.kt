package net.xzos.upgradeall.core.websdk.api.client_proxy

import net.xzos.upgradeall.core.utils.constant.VERSION_CODE
import net.xzos.upgradeall.core.websdk.json.AssetGson
import net.xzos.upgradeall.core.websdk.json.ReleaseGson
import org.dom4j.DocumentException
import org.dom4j.io.SAXReader
import org.intellij.markdown.flavours.commonmark.CommonMarkFlavourDescriptor
import org.intellij.markdown.html.HtmlGenerator
import org.intellij.markdown.parser.MarkdownParser
import java.net.URI
import java.time.Instant
import java.time.format.DateTimeFormatter

fun String.mdToHtml(): String {
    val flavour = CommonMarkFlavourDescriptor()
    val parsedTree = MarkdownParser(flavour).buildMarkdownTreeFromString(this)
    return HtmlGenerator(this, parsedTree, flavour).generateHtml()
}

fun String.getAssets(host: String, path: String): List<AssetGson> {
    val root = try {
        SAXReader().read(this.byteInputStream()).rootElement
    } catch (e: DocumentException) {
        return emptyList()
    }
    return root.selectNodes("//a").map {
        AssetGson(
            fileName = it.text,
            fileType = null,
            downloadUrl = getFullUrl(host, path, it.valueOf("./@href"))
        )
    }
}

fun String.tryGetTimestamp(): Long {
    return Instant.from(DateTimeFormatter.ISO_INSTANT.parse(this)).epochSecond
}

fun ReleaseGson.versionCode(value: Number?) = value?.let {
    this.copy(extra = mapOf(VERSION_CODE to it))
} ?: this

fun getFullUrl(host: String, patchPath: String, path: String): String {
    val url = URI(path)
    if (url.host != null) return path
    return URI(host).resolve("$patchPath/$path").toString()
}