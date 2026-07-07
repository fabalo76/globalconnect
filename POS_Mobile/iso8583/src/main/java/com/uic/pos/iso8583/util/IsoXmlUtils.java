package com.uic.pos.iso8583.util;

import java.util.ArrayList;
import java.util.List;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * DOM helper methods used to navigate ISO 8583 configuration files.
 *
 * @author UIC
 */
public final class IsoXmlUtils {
    private IsoXmlUtils() {
    }

    /**
     * Returns all direct child elements with the supplied name.
     *
     * @param parent the parent DOM element.
     * @param name   the node name to match.
     * @return an array containing the matching child elements.
     */
    public static Element[] getElementsByName(Element parent, String name) {
        NodeList nodes = parent.getChildNodes();
        List<Element> result = new ArrayList<>();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node instanceof Element && name.equals(node.getNodeName())) {
                result.add((Element) node);
            }
        }
        return result.toArray(new Element[0]);
    }

    /**
     * Reads the supplied attribute from the element, returning an empty string when absent.
     *
     * @param element   the element that may contain the attribute.
     * @param attribute the attribute name.
     * @return the attribute value or an empty string when missing.
     */
    public static String getAttribute(Element element, String attribute) {
        if (element == null || !element.hasAttribute(attribute)) {
            return "";
        }
        return element.getAttribute(attribute);
    }
}
