package org.exist.xmldb;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.File;
import javax.xml.parsers.*;
import org.apache.log4j.Logger;
import org.apache.xml.serialize.*;
import org.exist.EXistException;
import org.exist.dom.DocumentImpl;
import org.exist.dom.NodeProxy;
import org.exist.security.Permission;
import org.exist.security.PermissionDeniedException;
import org.exist.security.User;
import org.exist.storage.BrokerPool;
import org.exist.storage.DBBroker;
import org.exist.storage.serializers.Serializer;
import org.exist.util.XMLUtil;
import org.w3c.dom.*;
import org.xml.sax.*;
import org.xmldb.api.base.*;
import org.xmldb.api.modules.*;

/**
 * Local implementation of XMLResource.
 */
public class LocalXMLResource implements XMLResource {

	private static Logger LOG = Logger.getLogger(LocalXMLResource.class);

	protected BrokerPool brokerPool;
	protected String docId = null;
	protected DocumentImpl document = null;
	protected String encoding = "ISO-8859-1";
	protected long id = -1;
	protected LocalCollection parent;
	protected NodeProxy proxy = null;

	protected boolean saxDocEvents = true;
	protected boolean indent = true;
	protected boolean createContainerElements = true;
	protected boolean processXInclude = true;

	protected User user;
	protected String content = null;
	protected File file = null;

	public LocalXMLResource(
		User user,
		BrokerPool pool,
		LocalCollection parent,
		String docId,
		long id)
		throws XMLDBException {
		this(user, pool, parent, docId, id, true);
	}

	public LocalXMLResource(
		User user,
		BrokerPool pool,
		LocalCollection parent,
		String did,
		long id,
		boolean indent)
		throws XMLDBException {
		this.user = user;
		this.brokerPool = pool;
		this.parent = parent;
		this.id = id;
		if (did != null && did.indexOf('/') > -1)
			did = did.substring(did.lastIndexOf('/') + 1);

		this.docId = did;
		this.indent = indent;
	}

	public LocalXMLResource(
		User user,
		BrokerPool pool,
		LocalCollection parent,
		DocumentImpl doc,
		long id,
		boolean indent)
		throws XMLDBException {
		this.user = user;
		this.brokerPool = pool;
		this.parent = parent;
		this.id = id;
		this.document = doc;
		this.document.setDocumentElement(id);
		this.docId = doc.getFileName();
		if (docId.indexOf('/') > -1)
			docId = docId.substring(docId.lastIndexOf('/') + 1);

		this.indent = indent;
	}

	public LocalXMLResource(
		User user,
		BrokerPool pool,
		LocalCollection parent,
		NodeProxy p,
		boolean indent)
		throws XMLDBException {
		this(user, pool, parent, p.doc, p.gid, indent);
		this.proxy = p;
	}

	public Object getContent() throws XMLDBException {
		if (content != null)
			return content;
		else if (file != null) {
			try {
				content = XMLUtil.readFile(file);
				return content;
			} catch (IOException e) {
				throw new XMLDBException(
					ErrorCodes.VENDOR_ERROR,
					"error while reading resource contents");
			}
		} else {
			DBBroker broker = null;
			try {
				broker = brokerPool.get();
				if (document == null)
					getDocument(broker);
				if (!document.getPermissions().validate(user, Permission.READ))
					throw new XMLDBException(
						ErrorCodes.PERMISSION_DENIED,
						"permission denied to read resource");
				Serializer serializer = broker.getSerializer();
				serializer.setUser(user);
				serializer.setIndent(indent);
				serializer.setEncoding(encoding);
				serializer.setProcessXInclude(processXInclude);
				serializer.setCreateContainerElements(createContainerElements);
				if (id < 0)
					content = serializer.serialize(document);
				else {
					if (proxy == null)
						proxy = new NodeProxy(document, id);
					content = serializer.serialize(proxy);
				}
				return content;
			} catch (SAXException saxe) {
				saxe.printStackTrace();
				throw new XMLDBException(
					ErrorCodes.VENDOR_ERROR,
					saxe.getMessage());
			} catch (EXistException e) {
				throw new XMLDBException(
					ErrorCodes.VENDOR_ERROR,
					e.getMessage());
			} catch (Exception e) {
				e.printStackTrace();
				throw new XMLDBException(
					ErrorCodes.VENDOR_ERROR,
					e.getMessage());
			} finally {
				brokerPool.release(broker);
			}
		}
	}

	public Node getContentAsDOM() throws XMLDBException {
		DBBroker broker = null;
		try {
			broker = brokerPool.get();
			if (document == null)
				getDocument(broker);
			if (!document.getPermissions().validate(user, Permission.READ))
				throw new XMLDBException(
					ErrorCodes.PERMISSION_DENIED,
					"permission denied to read resource");
			if (id < 0)
				return document.getDocumentElement();
			else
				return document.getNode(id);
		} catch (EXistException e) {
			throw new XMLDBException(ErrorCodes.VENDOR_ERROR, e.getMessage());
		} finally {
			brokerPool.release(broker);
		}
	}

	public void getContentAsSAX(ContentHandler handler) throws XMLDBException {
		DBBroker broker = null;
		try {
			broker = brokerPool.get();
			if (document == null)
				getDocument(broker);
			if (!document.getPermissions().validate(user, Permission.READ))
				throw new XMLDBException(
					ErrorCodes.PERMISSION_DENIED,
					"permission denied to read resource");
			Serializer serializer = broker.getSerializer();
			serializer.setEncoding(encoding);
			serializer.setUser(user);
			serializer.setProcessXInclude(processXInclude);
			serializer.setContentHandler(handler);
			String xml;
			try {
				if (id < 0)
					serializer.toSAX(document, saxDocEvents);
				else {
					if (proxy == null)
						proxy = new NodeProxy(document, id);

					serializer.toSAX(proxy, saxDocEvents);
				}
			} catch (SAXException saxe) {
				saxe.printStackTrace();
				throw new XMLDBException(
					ErrorCodes.VENDOR_ERROR,
					saxe.getMessage());
			}
		} catch (EXistException e) {
			throw new XMLDBException(ErrorCodes.VENDOR_ERROR, e.getMessage());
		} finally {
			brokerPool.release(broker);
		}
	}

	protected DocumentImpl getDocument() {
		return document;
	}

	protected void getDocument(DBBroker broker) throws XMLDBException {
		if (document != null)
			return;
		try {
			String path =
				(parent.getPath().equals("/")
					? '/' + docId
					: parent.getPath() + '/' + docId);
			document = (DocumentImpl) broker.getDocument(path);
			if (document == null)
				throw new XMLDBException(ErrorCodes.INVALID_RESOURCE);
		} catch (PermissionDeniedException e) {
			throw new XMLDBException(ErrorCodes.PERMISSION_DENIED);
		}
	}

	protected NodeProxy getNode() {
		if (id < 0)
			// this XMLResource represents a document
			return null;
		getDocument();
		return proxy == null ? new NodeProxy(document, id) : proxy;
	}

	public String getDocumentId() throws XMLDBException {
		return docId;
	}

	public String getId() throws XMLDBException {
		return id < 0 ? docId : Long.toString(id);
	}

	public Collection getParentCollection() throws XMLDBException {
		if (parent == null)
			throw new XMLDBException(
				ErrorCodes.VENDOR_ERROR,
				"collection parent is null");
		return parent;
	}

	public String getResourceType() throws XMLDBException {
		return "XMLResource";
	}

	/**
	 * Sets the content for this resource. If value is of type
		* File, it is directly passed to the parser when 
		* Collection.storeResource is called. Otherwise the method
		* tries to convert the value to String.
		*
		* Passing a File object should be preferred if the document
		* is large. The file's content will not be loaded into memory
		* but directly passed to a SAX parser.
	 *
	 * @param value the content value to set for the resource.
	 * @exception XMLDBException with expected error codes.<br />
	 *  <code>ErrorCodes.VENDOR_ERROR</code> for any vendor
	 *  specific errors that occur.<br /> 
	 */
	public void setContent(Object value) throws XMLDBException {
		content = null;
		if (value instanceof File)
			file = (File) value;
		else {
			content = value.toString();
		}
	}

	public void setContentAsDOM(Node root) throws XMLDBException {
		OutputFormat format = new OutputFormat("xml", encoding, false);
		InternalXMLSerializer xmlout = new InternalXMLSerializer(format);
		try {
            switch(root.getNodeType()) {
                case Node.ELEMENT_NODE:
                    xmlout.serialize((Element)root);
                    break;
                case Node.DOCUMENT_NODE:
                    xmlout.serialize((Document)root);
                    break;
                case Node.DOCUMENT_FRAGMENT_NODE:
                    xmlout.serialize((DocumentFragment)root);
                    break;
                default:
                    throw new XMLDBException(ErrorCodes.WRONG_CONTENT_TYPE,
                        "argument should be an Element, Document or DocumentFragment");
            }
		} catch (IOException ioe) {
			throw new XMLDBException(ErrorCodes.VENDOR_ERROR, ioe.getMessage());
		}
	}

	public ContentHandler setContentAsSAX() throws XMLDBException {
		OutputFormat format = new OutputFormat("xml", encoding, false);
		return new InternalXMLSerializer(format);
	}

	protected void setEncoding(String encoding) {
		this.encoding = encoding;
	}

	protected void setSAXDocEvents(boolean generate) {
		this.saxDocEvents = generate;
	}

	public void setCreateContainerElements(boolean createContainerElements) {
		this.createContainerElements = createContainerElements;
	}

	public void setProcessXInclude(boolean process) {
		processXInclude = process;
	}

	private class InternalXMLSerializer extends XMLSerializer {

		StringWriter writer = new StringWriter();

		public InternalXMLSerializer(OutputFormat format) {
			super(format);
			setOutputCharStream(writer);
		}

		/**
		 * @see org.xml.sax.DocumentHandler#endDocument()
		 */
		public void endDocument() throws SAXException {
			super.endDocument();
			content = writer.toString();
		}
	}
}
