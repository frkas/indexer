package org.alfresco.consulting.indexer.webscripts;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.alfresco.consulting.indexer.dao.IndexingDaoImpl;
import org.alfresco.consulting.indexer.entities.NodeEntity;
import org.alfresco.error.AlfrescoRuntimeException;
import org.alfresco.model.ContentModel;
import org.alfresco.repo.domain.node.NodeDAO;
import org.alfresco.repo.domain.qname.QNameDAO;
import org.alfresco.repo.site.SiteModel;
import org.alfresco.service.cmr.model.FileFolderService;
import org.alfresco.service.cmr.repository.ContentService;
import org.alfresco.service.cmr.repository.NodeRef;
import org.alfresco.service.cmr.repository.NodeService;
import org.alfresco.service.cmr.repository.StoreRef;
import org.alfresco.service.cmr.search.ResultSet;
import org.alfresco.service.cmr.search.SearchParameters;
import org.alfresco.service.cmr.search.SearchService;
import org.alfresco.service.namespace.DynamicNamespacePrefixResolver;
import org.alfresco.service.namespace.NamespaceService;
import org.alfresco.service.namespace.QName;
import org.alfresco.util.Pair;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.extensions.webscripts.Cache;
import org.springframework.extensions.webscripts.DeclarativeWebScript;
import org.springframework.extensions.webscripts.Status;
import org.springframework.extensions.webscripts.WebScriptRequest;

import freemarker.ext.beans.BeansWrapper;
import freemarker.template.TemplateHashModel;

/**
 * Renders out a list of nodes (UUIDs) that have been changed in Alfresco; the changes can affect:
 * - A node metadata
 * - Node content
 * - Node ACLs
 *
 * Please check src/main/amp/config/alfresco/extension/templates/webscripts/com/findwise/alfresco/changes.get.desc.xml
 * to know more about the RestFul interface to invoke the WebScript
 *
 * List of pending activities (or TODOs)
 * - Move private/static logic into the IndexingService
 * - Using JSON libraries (or StringBuffer), render out the payload without passing through FreeMarker template
 * - Wrap (or Proxy) IndexingDaoImpl into an IndexingService, which (optionally) performs any object manipulation
 */
public class NodeChangesWebScript extends DeclarativeWebScript {

	protected static final Log logger = LogFactory.getLog(NodeChangesWebScript.class);

	@Qualifier("SearchService")
	@Autowired
	protected SearchService _searchService;

	@Qualifier("NodeService")
	@Autowired
	protected NodeService _nodeService;

	@Qualifier("ContentService")
	@Autowired
	protected ContentService _contentService;

	@Qualifier("FileFolderService")
	@Autowired
	protected FileFolderService _fileFolderService;
	
	@Qualifier("NamespaceService")
	@Autowired
	protected NamespaceService _nameSpaceService;


	@Override
	protected Map<String, Object> executeImpl(WebScriptRequest req, Status status, Cache cache) {

		//Fetching request params
		Map<String, String> templateArgs = req.getServiceMatch().getTemplateVars();
		String storeId = templateArgs.get("storeId");
		String storeProtocol = templateArgs.get("storeProtocol");
		String lastTxnIdString = req.getParameter("lastTxnId");
		String lastAclChangesetIdString = req.getParameter("lastAclChangesetId");
		String maxTxnsString = req.getParameter("maxTxns");
		String maxAclChangesetsString = req.getParameter("maxAclChangesets");

		if(req.getParameter("reindexfrom") != null && !req.getParameter("reindexfrom").equals("")){

			Map<String, Object> model = reindex(req.getParameter("reindexfrom"), storeId, storeProtocol, req.getParameter("startIndex"), req.getParameter("toIndex"));

			return model;
		}

		//Parsing parameters passed from the WebScript invocation
		Long lastTxnId = (lastTxnIdString == null ? null : Long.valueOf(lastTxnIdString));
		Long lastAclChangesetId = (lastAclChangesetIdString == null ? null : Long.valueOf(lastAclChangesetIdString));
		Integer maxTxns = (maxTxnsString == null ? maxNodesPerTxns : Integer.valueOf(maxTxnsString));
		Integer maxAclChangesets = (maxAclChangesetsString == null ? maxNodesPerAcl : Integer.valueOf(maxAclChangesetsString));

		logger.debug(String.format("Invoking Changes Webscript, using the following params\n" +
				"lastTxnId: %s\n" +
				"lastAclChangesetId: %s\n" +
				"storeId: %s\n" +
				"storeProtocol: %s\n" +
				"maxTxns: %s\n", lastTxnId, lastAclChangesetId, storeId, storeProtocol, maxTxns));

		//Getting the Store ID on which the changes are requested
		Pair<Long,StoreRef> store = nodeDao.getStore(new StoreRef(storeProtocol, storeId));
		if(store == null)
		{
			throw new IllegalArgumentException("Invalid store reference: " + storeProtocol + "://" + storeId);
		}

		Set<NodeEntity> nodes = new HashSet<NodeEntity>();
		//Updating the last IDs being processed
		//Depending on params passed to the request, results will be rendered out
		if (lastTxnId == null) {
			lastTxnId = new Long(0);
		}
		List<NodeEntity> nodesFromTxns = indexingService.getNodesByTransactionId(store, lastTxnId, maxTxns);
		if (nodesFromTxns != null && nodesFromTxns.size() > 0) {
			nodes.addAll(nodesFromTxns);
			lastTxnId = nodesFromTxns.get(nodesFromTxns.size()-1).getTransactionId();
		}

		if (lastAclChangesetId == null) {
			lastAclChangesetId = new Long(0);
		}
		List<NodeEntity> nodesFromAcls = indexingService.getNodesByAclChangesetId(store, lastAclChangesetId, maxAclChangesets);
		if (nodesFromAcls != null && nodesFromAcls.size() > 0) {
			nodes.addAll(nodesFromAcls);
			lastAclChangesetId = nodesFromAcls.get(nodesFromAcls.size()-1).getAclChangesetId();
		}

		//Render them out
		Map<String, Object> model = new HashMap<String, Object>(1, 1.0f);
		model.put("qnameDao", qnameDao);
		model.put("nsResolver", namespaceService);
		model.put("nodes", nodes);
		model.put("lastTxnId", lastTxnId);
		model.put("lastAclChangesetId", lastAclChangesetId);
		model.put("storeId", storeId);
		model.put("storeProtocol", storeProtocol);
		model.put("propertiesUrlTemplate", propertiesUrlTemplate);

		//This allows to call the static method QName.createQName from the FTL template
		try {
			BeansWrapper wrapper = BeansWrapper.getDefaultInstance();
			TemplateHashModel staticModels = wrapper.getStaticModels();
			TemplateHashModel qnameStatics = (TemplateHashModel) staticModels.get("org.alfresco.service.namespace.QName");
			model.put("QName",qnameStatics);
		} catch (Exception e) {
			throw new AlfrescoRuntimeException(
					"Cannot add BeansWrapper for static QName.createQName method to be used from a Freemarker template", e);
		}

		logger.debug(String.format("Attaching %s nodes to the WebScript template", nodes.size()));

		return model;
	}


	private Map<String, Object> reindex(String path, String storeId, String storeProtocol, String startIndexString, String toIndexString){
		
		//Default values
		int startIndex = 0;
		int toIndex = 10;
		
		if(startIndexString != null && !startIndexString.equals("")){
			startIndex = Integer.parseInt(startIndexString);
		}
		if(toIndexString != null && !toIndexString.equals("")){
			toIndex = Integer.parseInt(toIndexString);
		}

		Map<String, Object> model = new HashMap<String, Object>(1, 1.0f);
		model.put("storeId", storeId);
		model.put("storeProtocol", storeProtocol);
		model.put("propertiesUrlTemplate", propertiesUrlTemplate);

		String searchString = "SELECT * FROM cmis:document D WHERE CONTAINS(D,'PATH: \"" + path + "//*\"') and not D.cmis:contentStreamMimeType='text/xml' ORDER BY cmis:creationDate";

		SearchParameters searchParameters = new SearchParameters();
		searchParameters.addStore(StoreRef.STORE_REF_WORKSPACE_SPACESSTORE);
		searchParameters.setLanguage(SearchService.LANGUAGE_CMIS_ALFRESCO);
		searchParameters.setQuery(searchString);
		searchParameters.setMaxItems(toIndex-startIndex+1);
		searchParameters.setSkipCount(startIndex);
		
		ResultSet rs = null;
		
		try{
		rs = _searchService.query(searchParameters);

		List<NodeRef> list = new ArrayList<>();

		list = rs.getNodeRefs();

		Set<NodeEntity> reindexnodes = new HashSet<NodeEntity>();
		
		for(int i=0;i<list.size();i++){

			Map<QName, Serializable> properties = _nodeService.getProperties(list.get(i));
			
			String primaryPath = _nodeService.getPath(list.get(i)).toPrefixString(_nameSpaceService);
			String contentType = "UNKNOWN";
			
			if(primaryPath.contains(wiki)){
				contentType = wiki;
			}else if(primaryPath.contains(blog)){
				contentType = blog;
			}else if(primaryPath.contains(discussion)){
				contentType = discussion;
			}else if(primaryPath.contains(documentLibrary)){
				contentType = content;
			}
			
			NodeEntity n = new NodeEntity();
			n.setUuid((String) properties.get(ContentModel.PROP_NODE_UUID));
			n.setTypeName(contentType);
			reindexnodes.add(n);
		}		

		model.put("reindexnodes", reindexnodes);
		}finally{
			if(rs != null){
				rs.close();
				rs = null;
			}
		}
		return model;
	}
	
	private NamespaceService namespaceService;
	private QNameDAO qnameDao;
	private IndexingDaoImpl indexingService;
	private NodeDAO nodeDao;

	private String propertiesUrlTemplate;
	private int maxNodesPerAcl = 1000;
	private int maxNodesPerTxns = 1000;
	
	private final String wiki = "cm:wiki";
	private final String blog = "cm:blog";
	private final String discussion = "cm:discussion";
	private final String documentLibrary = "cm:documentLibrary";
	private final String content = "cm:content";


	public void setNamespaceService(NamespaceService namespaceService) {
		this.namespaceService = namespaceService;
	}
	public void setQnameDao(QNameDAO qnameDao) {
		this.qnameDao = qnameDao;
	}
	public void setIndexingService(IndexingDaoImpl indexingService) {
		this.indexingService = indexingService;
	}
	public void setNodeDao(NodeDAO nodeDao) {
		this.nodeDao = nodeDao;
	}

	public void setPropertiesUrlTemplate(String propertiesUrlTemplate) {
		this.propertiesUrlTemplate = propertiesUrlTemplate;
	}

	public void setMaxNodesPerAcl(int maxNodesPerAcl) {
		this.maxNodesPerAcl = maxNodesPerAcl;
	}

	public void setMaxNodesPerTxns(int maxNodesPerTxns) {
		this.maxNodesPerTxns = maxNodesPerTxns;
	}
	private static DynamicNamespacePrefixResolver getNamespaceResolver() {
	    DynamicNamespacePrefixResolver resolver = new DynamicNamespacePrefixResolver(null);
	    resolver.registerNamespace(NamespaceService.CONTENT_MODEL_PREFIX, NamespaceService.CONTENT_MODEL_1_0_URI);
	    resolver.registerNamespace(NamespaceService.APP_MODEL_PREFIX, NamespaceService.APP_MODEL_1_0_URI);
	    resolver.registerNamespace(SiteModel.SITE_MODEL_PREFIX, SiteModel.SITE_MODEL_URL);
	    resolver.registerNamespace(NamespaceService.SYSTEM_MODEL_PREFIX,NamespaceService.SYSTEM_MODEL_1_0_URI);
	    resolver.registerNamespace(NamespaceService.FORUMS_MODEL_PREFIX, NamespaceService.FORUMS_MODEL_1_0_URI);
	    return resolver;
	}
}