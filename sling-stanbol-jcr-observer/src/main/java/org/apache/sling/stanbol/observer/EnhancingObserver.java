/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.sling.stanbol.observer;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import javax.jcr.Node;
import javax.jcr.PathNotFoundException;
import javax.jcr.Repository;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.ValueFormatException;
import javax.jcr.observation.Event;
import javax.jcr.observation.EventIterator;
import javax.jcr.observation.EventListener;
import javax.jcr.observation.ObservationManager;

import org.apache.clerezza.rdf.core.MGraph;
import org.apache.clerezza.rdf.core.UriRef;
import org.apache.clerezza.rdf.core.access.TcManager;
import org.apache.clerezza.rdf.core.serializedform.Serializer;
import org.apache.clerezza.rdf.utils.GraphNode;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Properties;
import org.apache.felix.scr.annotations.Property;
import org.apache.felix.scr.annotations.Reference;
import org.apache.sling.jcr.api.SlingRepository;
import org.apache.sling.stanbol.commons.Utils;
import org.apache.stanbol.enhancer.servicesapi.ContentItem;
import org.apache.stanbol.enhancer.servicesapi.EngineException;
import org.apache.stanbol.enhancer.servicesapi.EnhancementJobManager;
import org.apache.stanbol.enhancer.servicesapi.helper.InMemoryContentItem;
import org.osgi.service.component.ComponentContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/* Observe the jcr repo for changes and generate
enhancements with Stanbol */
@Component(immediate = true, metatype = false)
@Properties({
	@Property(name = "service.description", value = "Listener generating metadata with Stanbol"),
	@Property(name = "service.vendor", value = "The Apache Software Foundation")
})
@SuppressWarnings("serial")
public class EnhancingObserver implements EventListener, Runnable {

	@Reference
	private SlingRepository repository;
	
	@Reference
	EnhancementJobManager ejm;
	
	@Reference
	Serializer serializer;
	
	@Reference
	TcManager tcManager;
	
	@Property(value = "/")
	private static final String CONTENT_PATH_PROPERTY = "content.path";
	
	private static final Logger log = LoggerFactory.getLogger(EnhancingObserver.class);
	
	private Session session;
	
	private ObservationManager observationManager;

	private Set<Node> pendingNodes = new HashSet<Node>();


	
	private Thread thread;

	protected void activate(ComponentContext context) throws Exception {
		//supportedMimeTypes.put("image/jpeg", ".jpg");
		//supportedMimeTypes.put("image/png", ".png");

		String contentPath = (String) context.getProperties().get(CONTENT_PATH_PROPERTY);

		session = repository.loginAdministrative(null);
		if (repository.getDescriptor(Repository.OPTION_OBSERVATION_SUPPORTED).equals("true")) {
			observationManager = session.getWorkspace().getObservationManager();
			String[] types = {"nt:file", "nt:resource"};
			observationManager.addEventListener(this,
					Event.NODE_ADDED | Event.PROPERTY_CHANGED | Event.PROPERTY_ADDED,
					contentPath, true, null, types, false);
		} else {
			log.warn("Obervation is not supported");
		}
		thread = new Thread(this);
		thread.start();
	}

	protected void deactivate(ComponentContext componentContext) throws RepositoryException {
		if (observationManager != null) {
			observationManager.removeEventListener(this);
		}
		if (session != null) {
			session.logout();
			session = null;
		}
		thread.interrupt();
	}

	public void onEvent(EventIterator ei) {
		while (ei.hasNext()) {
			Event event = ei.nextEvent();
			try {
				if (event.getPath().endsWith("jcr:data")) {
					String propertyPath = event.getPath();
					Node addedNode = session.getRootNode().getNode(
							propertyPath.substring(1, propertyPath.indexOf("/jcr:content")));
					synchronized(pendingNodes) {
						pendingNodes.add(addedNode);
					}
					synchronized(this) {
						notifyAll();
					}
					
				}
			} catch (Exception e) {
				log.error(e.getMessage(), e);
			}
		}
	}
	
	

	public void run() {
		while (!Thread.currentThread().isInterrupted()) {
			synchronized(this) {
				try {
					wait();
					Thread.sleep(1000);
				} catch (InterruptedException e) {
					thread.interrupt();
				}
			}
			Set<Node> processingNode = new HashSet<Node>();
			synchronized(pendingNodes) {
				processingNode.addAll(pendingNodes);
				pendingNodes.clear();
			}
			for (Node node : processingNode) {
				processNode(node);
			}
		}
	}

	private void processNode(Node node) {
		try {
			final String content = node.getProperty("jcr:content/jcr:data").getString();
			String mimeType = getMimeType(node);
			UriRef contentUri = getUri(node);
			ContentItem c = new InMemoryContentItem(contentUri.getUnicodeString(), content.getBytes(), mimeType);
			try {
				ejm.enhanceContent(c);
			} catch (EngineException ex) {
				log.error("Exception enhancing "+node, ex);
				return;
			}
			MGraph metadata = c.getMetadata();
			MGraph enhancementMGraph = Utils.getEnhancementMGraph(tcManager);
			GraphNode contentGN = new GraphNode(contentUri, enhancementMGraph);
			Iterator<GraphNode> enhancementsIter = contentGN.getSubjectNodes(org.apache.stanbol.enhancer.servicesapi.rdf.Properties.ENHANCER_EXTRACTED_FROM);
			//adding to set to avoid concurrent modification
			Set<GraphNode> enhancements = new HashSet<GraphNode>();
			while (enhancementsIter.hasNext()) {
				GraphNode enhancement = enhancementsIter.next();
				enhancements.add(enhancement);
			}
			for (GraphNode enhancement : enhancements) {
				enhancement.deleteNodeContext();
			}
			contentGN.deleteNodeContext();
			enhancementMGraph.addAll(metadata);
			log.info("accumulated metadata size: {}.", enhancementMGraph.size());
			//serializer.serialize(System.out, metadata, SupportedFormat.RDF_XML);
		} catch (RepositoryException ex) {
			throw new RuntimeException(ex);
		}
	}
	

	private String getMimeType(Node n) throws ValueFormatException, RepositoryException {
		try {
			return n.getProperty("jcr:content/jcr:mimeType").getString();
		} catch (PathNotFoundException ex) {
			return "application/octet-stream";
		}
	}

	public static UriRef getUri(Node node) throws RepositoryException {
		return Utils.getUri(node.getPath());
	}
}
