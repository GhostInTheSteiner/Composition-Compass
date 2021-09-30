package com.gits.compositioncompass.StuffJavaIsTooConvolutedFor

import org.w3c.dom.Node
import org.w3c.dom.NodeList
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.xpath.XPath
import javax.xml.xpath.XPathConstants
import javax.xml.xpath.XPathFactory

class NodeQuery {

    private val node: Node
    private val xPath: XPath

    val item: Node
        get() = node

    constructor(node: Node) {
        this.node = node
        xPath = XPathFactory.newInstance().newXPath();
    }

    constructor(xml: String) {
        node = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(InputSource(StringReader(xml)));
        xPath = XPathFactory.newInstance().newXPath();
    }

    fun get(path: String): List<NodeQuery> {
        val expr = xPath.compile(path)
        val nodeList = expr.evaluate(node, XPathConstants.NODESET) as NodeList
        val resultList = mutableListOf<NodeQuery>()

        for (i in 0..nodeList.length-1) {
            resultList.add(NodeQuery(nodeList.item(i)))
        }

        return resultList
    }

    fun getText(path: String) =
        get(path).first().item.textContent

}