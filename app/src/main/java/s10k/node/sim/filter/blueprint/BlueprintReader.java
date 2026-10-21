/* ==================================================================
 * BlueprintReader.java - 21 Oct 2026 2:05:41 pm
 *
 * Copyright 2026 SolarNetwork.net Dev Team
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * ==================================================================
 */

package s10k.node.sim.filter.blueprint;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.jspecify.annotations.Nullable;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;
import s10k.node.sim.filter.blueprint.Blueprint.Bean;
import s10k.node.sim.filter.blueprint.Blueprint.BeanValue;
import s10k.node.sim.filter.blueprint.Blueprint.ListValue;
import s10k.node.sim.filter.blueprint.Blueprint.Literal;
import s10k.node.sim.filter.blueprint.Blueprint.ManagedServiceFactory;
import s10k.node.sim.filter.blueprint.Blueprint.MapValue;
import s10k.node.sim.filter.blueprint.Blueprint.Ref;
import s10k.node.sim.filter.blueprint.Blueprint.Reference;
import s10k.node.sim.filter.blueprint.Blueprint.ReferenceValue;
import s10k.node.sim.filter.blueprint.Blueprint.Service;
import s10k.node.sim.filter.blueprint.Blueprint.Unsupported;
import s10k.node.sim.filter.blueprint.Blueprint.Value;

/**
 * Read OSGi blueprint XML, as used by SolarNode filter bundles, with the JDK
 * DOM API.
 *
 * <p>
 * Only the subset of the blueprint and Gemini Blueprint compendium schemas that
 * filter bundles use is supported: top-level beans, references, services, and
 * managed service factories. Other top-level elements are ignored, and
 * unsupported values are read as {@link Unsupported}, so that the
 * {@link FilterBlueprintInterpreter} can report them if a filter uses them.
 * </p>
 *
 * @author matt
 * @version 1.0
 */
public final class BlueprintReader {

	/** The OSGi blueprint XML namespace. */
	public static final String BLUEPRINT_NS = "http://www.osgi.org/xmlns/blueprint/v1.0.0";

	/** The Gemini Blueprint compendium XML namespace. */
	public static final String COMPENDIUM_NS = "http://www.eclipse.org/gemini/blueprint/schema/blueprint-compendium";

	private final DocumentBuilderFactory factory;

	/**
	 * Constructor.
	 */
	public BlueprintReader() {
		super();
		factory = DocumentBuilderFactory.newInstance();
		factory.setNamespaceAware(true);
		factory.setXIncludeAware(false);
		factory.setExpandEntityReferences(false);
		try {
			factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
			factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
		} catch ( ParserConfigurationException e ) {
			throw new IllegalStateException("XML parser does not support secure processing.", e);
		}
	}

	/**
	 * Read a blueprint XML document.
	 *
	 * @param in
	 *        the XML to read; the caller must close it
	 * @return the blueprint
	 * @throws IOException
	 *         if the XML cannot be read or parsed
	 */
	public Blueprint read(InputStream in) throws IOException {
		final Document doc;
		try {
			DocumentBuilder builder = factory.newDocumentBuilder();
			doc = builder.parse(in);
		} catch ( ParserConfigurationException e ) {
			throw new IllegalStateException("XML parser not available.", e);
		} catch ( SAXException e ) {
			throw new IOException("Invalid blueprint XML: " + e.getMessage(), e);
		}
		final Element root = doc.getDocumentElement();
		if ( !is(root, BLUEPRINT_NS, "blueprint") ) {
			throw new IOException(
					"Not a blueprint document: root element is <%s>.".formatted(root.getNodeName()));
		}
		final Map<String, Bean> beans = new LinkedHashMap<>();
		final Map<String, Reference> references = new LinkedHashMap<>();
		final List<Service> services = new ArrayList<>();
		final List<ManagedServiceFactory> factories = new ArrayList<>();
		final List<String> problems = new ArrayList<>();
		for ( Element el : children(root) ) {
			if ( is(el, BLUEPRINT_NS, "bean") ) {
				Bean bean = bean(el);
				if ( bean.id() != null && beans.putIfAbsent(bean.id(), bean) != null ) {
					problems.add("Duplicate component ID [%s].".formatted(bean.id()));
				}
			} else if ( is(el, BLUEPRINT_NS, "reference") || is(el, BLUEPRINT_NS, "reference-list") ) {
				Reference ref = reference(el);
				if ( ref.id() != null && references.putIfAbsent(ref.id(), ref) != null ) {
					problems.add("Duplicate component ID [%s].".formatted(ref.id()));
				}
			} else if ( is(el, BLUEPRINT_NS, "service") ) {
				services.add(service(el));
			} else if ( is(el, COMPENDIUM_NS, "managed-service-factory") ) {
				factories.add(managedServiceFactory(el));
			}
		}
		return new Blueprint(Collections.unmodifiableMap(beans), Collections.unmodifiableMap(references),
				List.copyOf(services), List.copyOf(factories), List.copyOf(problems));
	}

	private static Bean bean(Element el) {
		final List<Value> arguments = new ArrayList<>(4);
		final Map<String, Value> properties = new LinkedHashMap<>();
		for ( Element child : children(el) ) {
			switch (child.getLocalName()) {
				case "argument" -> arguments.add(attributeOrChildValue(child));
				case "property" -> properties.put(child.getAttribute("name"),
						attributeOrChildValue(child));
				default -> {
					// ignore others, like osgix:managed-properties
				}
			}
		}
		return new Bean(attr(el, "id"), attr(el, "class"), attr(el, "factory-method"),
				attr(el, "factory-ref"), attr(el, "init-method"), attr(el, "destroy-method"),
				List.copyOf(arguments), Collections.unmodifiableMap(properties));
	}

	private static Reference reference(Element el) {
		return new Reference(attr(el, "id"), attr(el, "interface"), attr(el, "filter"),
				"reference-list".equals(el.getLocalName()));
	}

	private static Service service(Element el) {
		final List<String> interfaces = new ArrayList<>(2);
		final String iface = attr(el, "interface");
		if ( iface != null ) {
			interfaces.add(iface);
		}
		Map<String, Value> props = Map.of();
		Bean bean = null;
		for ( Element child : children(el) ) {
			switch (child.getLocalName()) {
				case "interfaces" -> interfaces.addAll(interfaces(child));
				case "service-properties" -> props = entries(child);
				case "bean" -> bean = bean(child);
				default -> {
					// ignore others, like registration-listener
				}
			}
		}
		return new Service(attr(el, "ref"), List.copyOf(interfaces), props, bean);
	}

	private static ManagedServiceFactory managedServiceFactory(Element el) {
		final List<String> interfaces = new ArrayList<>(2);
		final String iface = attr(el, "interface");
		if ( iface != null ) {
			interfaces.add(iface);
		}
		Map<String, Value> props = Map.of();
		Bean bean = null;
		for ( Element child : children(el) ) {
			switch (child.getLocalName()) {
				case "interfaces" -> interfaces.addAll(interfaces(child));
				case "service-properties" -> props = entries(child);
				case "bean" -> bean = bean(child);
				default -> {
					// ignore others
				}
			}
		}
		return new ManagedServiceFactory(attr(el, "factory-pid"), List.copyOf(interfaces), props, bean);
	}

	private static List<String> interfaces(Element el) {
		final List<String> result = new ArrayList<>(2);
		for ( Element child : children(el) ) {
			if ( "value".equals(child.getLocalName()) ) {
				result.add(child.getTextContent().strip());
			}
		}
		return result;
	}

	private static Map<String, Value> entries(Element el) {
		final Map<String, Value> result = new LinkedHashMap<>();
		for ( Element child : children(el) ) {
			if ( "entry".equals(child.getLocalName()) ) {
				result.put(child.getAttribute("key"), attributeOrChildValue(child));
			}
		}
		return Collections.unmodifiableMap(result);
	}

	/**
	 * Get the value of an argument, property, or entry: from its {@code value}
	 * or {@code ref} attribute, or its first child element.
	 */
	private static Value attributeOrChildValue(Element el) {
		final String value = attr(el, "value");
		if ( value != null ) {
			return new Literal(value);
		}
		final String ref = attr(el, "ref");
		if ( ref != null ) {
			return new Ref(ref);
		}
		final List<Element> children = children(el);
		if ( children.isEmpty() ) {
			return new Unsupported("<%s> without a value".formatted(el.getLocalName()));
		}
		return value(children.getFirst());
	}

	private static Value value(Element el) {
		return switch (el.getLocalName()) {
			case "value" -> new Literal(el.getTextContent().strip());
			case "ref" -> {
				// blueprint uses component-id, Spring beans uses bean
				String id = attr(el, "component-id");
				if ( id == null ) {
					id = attr(el, "bean");
				}
				yield (id != null ? new Ref(id) : new Unsupported("<ref> without an ID"));
			}
			case "bean" -> new BeanValue(bean(el));
			case "reference", "reference-list" -> new ReferenceValue(reference(el));
			case "list", "array", "set" -> {
				final List<Value> values = new ArrayList<>(4);
				for ( Element child : children(el) ) {
					values.add(value(child));
				}
				yield new ListValue(List.copyOf(values));
			}
			case "map" -> new MapValue(entries(el));
			default -> new Unsupported("<%s>".formatted(el.getLocalName()));
		};
	}

	private static boolean is(Element el, String ns, String localName) {
		return ns.equals(el.getNamespaceURI()) && localName.equals(el.getLocalName());
	}

	private static @Nullable String attr(Element el, String name) {
		return (el.hasAttribute(name) ? el.getAttribute(name) : null);
	}

	private static List<Element> children(Element el) {
		final List<Element> result = new ArrayList<>(8);
		for ( Node n = el.getFirstChild(); n != null; n = n.getNextSibling() ) {
			if ( n instanceof Element child ) {
				result.add(child);
			}
		}
		return result;
	}

}
