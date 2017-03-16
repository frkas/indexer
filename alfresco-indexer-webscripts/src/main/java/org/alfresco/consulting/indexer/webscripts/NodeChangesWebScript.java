package org.alfresco.consulting.indexer.webscripts;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.alfresco.consulting.indexer.dao.IndexingDaoImpl;
import org.alfresco.consulting.indexer.entities.NodeEntity;
import org.alfresco.error.AlfrescoRuntimeException;
import org.alfresco.model.ContentModel;
import org.alfresco.repo.domain.node.NodeDAO;
import org.alfresco.repo.domain.node.StoreEntity;
import org.alfresco.repo.domain.qname.QNameDAO;
import org.alfresco.service.cmr.model.FileFolderService;
import org.alfresco.service.cmr.repository.ContentService;
import org.alfresco.service.cmr.repository.NodeRef;
import org.alfresco.service.cmr.repository.NodeService;
import org.alfresco.service.cmr.repository.StoreRef;
import org.alfresco.service.cmr.search.LimitBy;
import org.alfresco.service.cmr.search.ResultSet;
import org.alfresco.service.cmr.search.SearchParameters;
import org.alfresco.service.cmr.search.SearchService;
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
 * Renders out a list of nodes (UUIDs) that have been changed in Alfresco; the
 * changes can affect: - A node metadata - Node content - Node ACLs
 *
 * Please check
 * src/main/amp/config/alfresco/extension/templates/webscripts/com/findwise/
 * alfresco/changes.get.desc.xml to know more about the RestFul interface to
 * invoke the WebScript
 *
 * List of pending activities (or TODOs) - Move private/static logic into the
 * IndexingService - Using JSON libraries (or StringBuffer), render out the
 * payload without passing through FreeMarker template - Wrap (or Proxy)
 * IndexingDaoImpl into an IndexingService, which (optionally) performs any
 * object manipulation
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

		// Fetching request params
		Map<String, String> templateArgs = req.getServiceMatch().getTemplateVars();
		String storeId = templateArgs.get("storeId");
		String storeProtocol = templateArgs.get("storeProtocol");
		String lastTxnIdString = req.getParameter("lastTxnId");
		String lastOtherTxnIdString = req.getParameter("lastOtherTxnId");
		String lastAclChangesetIdString = req.getParameter("lastAclChangesetId");
		String maxTxnsString = req.getParameter("maxTxns");
		String maxOtherTxnsString = req.getParameter("maxOtherTxns");
		String maxAclChangesetsString = req.getParameter("maxAclChangesets");

		//Will be deprecated
		if (req.getParameter("reindexfrom") != null && !req.getParameter("reindexfrom").equals("")) {

			Map<String, Object> model = reindexDocuments(req.getParameter("reindexfrom"), storeId,
					storeProtocol, req.getParameter("startIndex"), req.getParameter("toIndex"));

			return model;
		}
		if (req.getParameter("reindexDocumentsFrom") != null && !req.getParameter("reindexDocumentsFrom").equals("")) {

			Map<String, Object> model = reindexDocuments(req.getParameter("reindexDocumentsFrom"), storeId,
					storeProtocol, req.getParameter("startIndex"), req.getParameter("toIndex"));

			return model;
		}
		if (req.getParameter("reindexFoldersFrom") != null && !req.getParameter("reindexFoldersFrom").equals("")) {

			Map<String, Object> model = reindexFolders(req.getParameter("reindexFoldersFrom"), storeId, storeProtocol,
					req.getParameter("startIndex"), req.getParameter("toIndex"));

			return model;
		}

		//Check if we should reindex profiles
		Map<String, String> templateVars = req.getServiceMatch().getTemplateVars();
		String reindexProfiles = (String) templateVars.get("reindexProfiles");
		if (reindexProfiles != null && reindexProfiles.equals("reindexProfiles")) {

			Map<String, Object> model = reindexProfiles(req.getParameter("startIndex"), req.getParameter("toIndex"));

			return model;
		}

		// Parsing parameters passed from the WebScript invocation
		Long lastTxnId = (lastTxnIdString == null ? null : Long.valueOf(lastTxnIdString));
		Long lastOtherTxnId = (lastOtherTxnIdString == null ? null : Long.valueOf(lastOtherTxnIdString));
		Long lastAclChangesetId = (lastAclChangesetIdString == null ? null : Long.valueOf(lastAclChangesetIdString));
		Integer maxTxns = (maxTxnsString == null ? maxNodesPerTxns : Integer.valueOf(maxTxnsString));
		Integer maxOtherTxns = (maxOtherTxnsString == null ? maxNodesPerOtherTxns
				: Integer.valueOf(maxOtherTxnsString));
		Integer maxAclChangesets = (maxAclChangesetsString == null ? maxNodesPerAcl
				: Integer.valueOf(maxAclChangesetsString));

		//Get the lowest startTransactionId for the deletion part of code
		Long startTransactionId = lastTxnId;
		if(startTransactionId > lastOtherTxnId){
			startTransactionId = lastOtherTxnId;
		}

		// Getting the Store ID on which the changes are requested
		Pair<Long, StoreRef> store = nodeDao.getStore(new StoreRef(storeProtocol, storeId));
		if (store == null) {
			throw new IllegalArgumentException("Invalid store reference: " + storeProtocol + "://" + storeId);
		}

		Set<NodeEntity> nodes = new HashSet<NodeEntity>();
		// Updating the last IDs being processed
		// Depending on params passed to the request, results will be rendered
		// out
		if (lastTxnId == null) {
			lastTxnId = new Long(0);
		}


		List<NodeEntity> nodesFromTxns = indexingService.getNodesByTransactionId(store, lastTxnId, maxTxns);
		if (nodesFromTxns != null && nodesFromTxns.size() > 0) {
			nodes.addAll(nodesFromTxns);
			lastTxnId = nodesFromTxns.get(nodesFromTxns.size() - 1).getTransactionId();
		}

		if (lastAclChangesetId == null) {
			lastAclChangesetId = new Long(0);
		}
		List<NodeEntity> nodesFromAcls = indexingService.getNodesByAclChangesetId(store, lastAclChangesetId,
				maxAclChangesets);

		if (nodesFromAcls != null && nodesFromAcls.size() > 0) {
			nodes.addAll(nodesFromAcls);
			lastAclChangesetId = nodesFromAcls.get(nodesFromAcls.size() - 1).getAclChangesetId();
		}

		if (lastOtherTxnId == null) {
			lastOtherTxnId = new Long(0);
		}
		List<NodeEntity> nodesFromOtherTxns = indexingService.getNodesByOtherTransactionId(store, lastOtherTxnId,
				maxOtherTxns);
		if (nodesFromOtherTxns != null && nodesFromOtherTxns.size() > 0) {
			nodes.addAll(nodesFromOtherTxns);
			lastOtherTxnId = nodesFromOtherTxns.get(nodesFromOtherTxns.size() - 1).getTransactionId();
		}

		//Get the higest last transaction id
		Long lastTransactionId = lastTxnId;
		if(lastTransactionId < lastOtherTxnId){
			lastTransactionId = lastOtherTxnId;
		}

		//Calculate maxTransactionId
		Long maxTransactionsLong = lastTransactionId - startTransactionId;
		int maxTransactions = maxTransactionsLong.intValue();
		if(maxTransactions <= 0){
			maxTransactions = 1;
		}

		List<NodeEntity> deletedNodes = indexingService.getNodesByDeletedObjectsTransactionId(store, startTransactionId,
				maxTransactions);

		String userName = "";

		String propertiesUrl = "";

		//Iterate all changed nodes to see if deleted is there or not. If its not add it
		//Also check if there is a person or trrr. Need to change propertiesurl
		Iterator<NodeEntity> itChangedNodes = nodes.iterator();
		while (itChangedNodes.hasNext()) {

			NodeEntity changedNodeEntity = itChangedNodes.next();

			String changedNodeUUID = changedNodeEntity.getUuid();

			if(changedNodeEntity.getTypeName().equals("person")){

				NodeRef personNode = new NodeRef("workspace://SpacesStore/"+ changedNodeUUID);

				Map<QName, Serializable> properties = _nodeService.getProperties(personNode);

				userName = properties.get(ContentModel.PROP_USERNAME).toString();

				changedNodeEntity.setUserName(userName);

				propertiesUrl = getPersonPropertiesUrlTemplate() + userName;

			}else if(changedNodeEntity.getTypeName().equals("reflexResource")){
				
				NodeRef trrrNode = new NodeRef("workspace://SpacesStore/"+ changedNodeUUID);

				//Check if exists, If not its in versionstore then its deleted
				if(!_nodeService.exists(trrrNode)){
					changedNodeEntity.setDeleted(true);
					propertiesUrl = null;
					
				}else{

					Map<QName, Serializable> properties = _nodeService.getProperties(trrrNode);

					String URI = "http://www.scania.com/model/teamroom-reflex-resources/1.0";
					QName TRRR_PROP_NAME = QName.createQName(URI, "name");

					String trrrName = properties.get(TRRR_PROP_NAME).toString();

					propertiesUrl = getTrrrPropertiesUrlTemplate() + trrrName;
				}
			}else{
				userName = "";
				propertiesUrl = propertiesUrlTemplate + "/" + storeProtocol + "/" + storeId + "/" + changedNodeUUID; 
			}
			
			changedNodeEntity.setPropertiesUrl(propertiesUrl);
			
			Iterator<NodeEntity> itDeletedNodes = deletedNodes.iterator();

			int i = 0;
			while (itDeletedNodes.hasNext()) {

				NodeEntity deletedNodeEntity = itDeletedNodes.next();
				String deletedNodeUUID = deletedNodeEntity.getUuid();			

				if(changedNodeUUID.equals(deletedNodeUUID)){
					//Remove from list
					deletedNodes.remove(i);
					changedNodeEntity.setDeleted(true);
					break;
				}
				i++;
			}
		}

		//Iterate all deleted and set deleted = true
		Iterator<NodeEntity> itDeletedNodes = deletedNodes.iterator();
		itDeletedNodes = deletedNodes.iterator();
		while (itDeletedNodes.hasNext()) {

			NodeEntity deletedNodeEntity = itDeletedNodes.next();
			deletedNodeEntity.setDeleted(true);
		}

		nodes.addAll(deletedNodes);

		// Iterate all nodes. The getDeleted method in NodeEntity is not working
		// correctly
		Iterator<NodeEntity> it = nodes.iterator();
		while (it.hasNext()) {

			NodeEntity nodeEntity = it.next();

			StoreEntity storeEntiti = nodeEntity.getStore();
			if(storeEntiti.getId().intValue() == 5){
				nodeEntity.setDeleted(true);
			}
		}

		// Render them out
		Map<String, Object> model = new HashMap<String, Object>(1, 1.0f);
		model.put("qnameDao", qnameDao);
		model.put("nsResolver", namespaceService);
		model.put("nodes", nodes);
		model.put("lastTxnId", lastTxnId);
		model.put("lastOtherTxnId", lastOtherTxnId);
		model.put("lastAclChangesetId", lastAclChangesetId);
		model.put("storeId", storeId);
		model.put("storeProtocol", storeProtocol);
		
		// This allows to call the static method QName.createQName from the FTL
		// template
		try {
			BeansWrapper wrapper = BeansWrapper.getDefaultInstance();
			TemplateHashModel staticModels = wrapper.getStaticModels();
			TemplateHashModel qnameStatics = (TemplateHashModel) staticModels
					.get("org.alfresco.service.namespace.QName");
			model.put("QName", qnameStatics);
		} catch (Exception e) {
			throw new AlfrescoRuntimeException(
					"Cannot add BeansWrapper for static QName.createQName method to be used from a Freemarker template",
					e);
		}

		return model;
	}

	private Map<String, Object> reindexProfiles(String startIndexString, String toIndexString){

		Map<String, Object> model = new HashMap<String, Object>(1, 1.0f);
		model.put("storeId", "spacesStore");
		model.put("storeProtocol", "workspace");
		model.put("personPropertiesUrlTemplate", personPropertiesUrlTemplate);
		model.put("propertiesUrlTemplate", propertiesUrlTemplate);

		String searchString = "SELECT * FROM cm:person";

		// Default values
		int startIndex = 0;
		int toIndex = 1000;

		if (startIndexString != null && !startIndexString.equals("")) {
			startIndex = Integer.parseInt(startIndexString);
		}
		if (toIndexString != null && !toIndexString.equals("")) {
			toIndex = Integer.parseInt(toIndexString);
		}
		int maxindex = toIndex - startIndex + 1;

		SearchParameters searchParameters = new SearchParameters();
		searchParameters.addStore(StoreRef.STORE_REF_WORKSPACE_SPACESSTORE);
		searchParameters.setLanguage(SearchService.LANGUAGE_CMIS_ALFRESCO);
		searchParameters.setQuery(searchString);
		searchParameters.setMaxItems(toIndex - startIndex + 1);
		searchParameters.setSkipCount(startIndex);

		searchParameters.setLimitBy(LimitBy.UNLIMITED);
		searchParameters.setLimit(0);
		searchParameters.setMaxPermissionChecks(100000);
		searchParameters.setMaxPermissionCheckTimeMillis(100000);

		List<NodeRef> list = new ArrayList<>();

		ResultSet rs = null;

		try {
			rs = _searchService.query(searchParameters);

			list = rs.getNodeRefs();

		} finally {
			if (rs != null) {
				rs.close();
				rs = null;
			}
		}

		Set<NodeEntity> reindexPersonsNodes = new HashSet<NodeEntity>();

		for (int i = 0; i < list.size(); i++) {

			Map<QName, Serializable> properties = _nodeService.getProperties(list.get(i));

			NodeEntity n = new NodeEntity();

			String userName = properties.get(ContentModel.PROP_USERNAME).toString();

			n.setUserName(userName);
			n.setUuid(properties.get(ContentModel.PROP_NODE_UUID).toString());
			n.setTypeName("cm:person");

			reindexPersonsNodes.add(n);
		}

		model.put("reindexnodes", reindexPersonsNodes);
		return model;
	}

	private List<NodeRef> getListOfNodeRefsFromSearchString(String searchString, String startIndexString,
			String toIndexString) {

		// Default values
		int startIndex = 0;
		int toIndex = 1000;

		if (startIndexString != null && !startIndexString.equals("")) {
			startIndex = Integer.parseInt(startIndexString);
		}
		if (toIndexString != null && !toIndexString.equals("")) {
			toIndex = Integer.parseInt(toIndexString);
		}

		SearchParameters searchParameters = new SearchParameters();
		searchParameters.addStore(StoreRef.STORE_REF_WORKSPACE_SPACESSTORE);
		searchParameters.setLanguage(SearchService.LANGUAGE_CMIS_ALFRESCO);
		searchParameters.setQuery(searchString);
		searchParameters.setMaxItems(toIndex - startIndex + 1);
		searchParameters.setSkipCount(startIndex);

		ResultSet rs = null;

		try {
			rs = _searchService.query(searchParameters);

			List<NodeRef> list = new ArrayList<>();

			list = rs.getNodeRefs();

			return list;

		} finally {
			if (rs != null) {
				rs.close();
				rs = null;
			}
		}

	}

	private Map<String, Object> reindexDocuments(String path, String storeId, String storeProtocol,
			String startIndexString, String toIndexString) {

		Map<String, Object> model = new HashMap<String, Object>(1, 1.0f);
		model.put("storeId", storeId);
		model.put("storeProtocol", storeProtocol);
		model.put("propertiesUrlTemplate", propertiesUrlTemplate);

		String searchString = "SELECT * FROM cmis:document D WHERE CONTAINS(D,'PATH: \"" + path
				+ "//*\"') and not D.cmis:contentStreamMimeType='text/xml' ORDER BY cmis:creationDate";

		List<NodeRef> list = getListOfNodeRefsFromSearchString(searchString, startIndexString, toIndexString);

		Set<NodeEntity> reindexnodes = new HashSet<NodeEntity>();

		for (int i = 0; i < list.size(); i++) {

			Map<QName, Serializable> properties = _nodeService.getProperties(list.get(i));

			String primaryPath = _nodeService.getPath(list.get(i)).toPrefixString(_nameSpaceService);
			String contentType = "UNKNOWN";

			if (primaryPath.contains(wiki)) {
				contentType = wiki;
			} else if (primaryPath.contains(blog)) {
				contentType = blog;
			} else if (primaryPath.contains(discussion)) {
				contentType = discussion;
			} else if (primaryPath.contains(documentLibrary) || primaryPath.contains(published)) {
				contentType = content;
			}

			NodeEntity n = new NodeEntity();
			n.setUuid((String) properties.get(ContentModel.PROP_NODE_UUID));
			n.setTypeName(contentType);
			reindexnodes.add(n);
		}

		// If startindex is 0 or less its the first page and we also return
		// folder/site id
		if (startIndexString == null || startIndexString.equals("") || startIndexString.equals("0")) {
			StoreRef storeRef = new StoreRef(StoreRef.PROTOCOL_WORKSPACE, "SpacesStore");
			ResultSet rsRootFolder = _searchService.query(storeRef, SearchService.LANGUAGE_LUCENE,
					"PATH:\"" + path + "\"");

			try {
				if (rsRootFolder.length() >= 0) {

					NodeRef nodeRefRootFolder = rsRootFolder.getNodeRef(0);
					NodeEntity n = new NodeEntity();
					n.setUuid((String) nodeRefRootFolder.getId());

					if (path.contains("cm:documentLibrary")) {
						n.setTypeName("cm:folder");
					} else {
						n.setTypeName("st:site");
					}

					reindexnodes.add(n);
				}

			} finally {
				rsRootFolder.close();
			}
		}

		model.put("reindexnodes", reindexnodes);
		return model;
	}

	private Map<String, Object> reindexFolders(String path, String storeId, String storeProtocol,
			String startIndexString, String toIndexString) {

		Map<String, Object> model = new HashMap<String, Object>(1, 1.0f);
		model.put("storeId", storeId);
		model.put("storeProtocol", storeProtocol);
		model.put("propertiesUrlTemplate", propertiesUrlTemplate);

		String searchString = "SELECT * FROM cmis:folder F WHERE CONTAINS(F,'PATH: \"" + path
				+ "//*\"') ORDER BY cmis:creationDate";

		List<NodeRef> list = getListOfNodeRefsFromSearchString(searchString, startIndexString, toIndexString);

		Set<NodeEntity> reindexnodes = new HashSet<NodeEntity>();

		for (int i = 0; i < list.size(); i++) {

			Map<QName, Serializable> properties = _nodeService.getProperties(list.get(i));

			NodeEntity n = new NodeEntity();
			n.setUuid((String) properties.get(ContentModel.PROP_NODE_UUID));
			n.setTypeName("cm:folder");
			reindexnodes.add(n);
		}

		// If startindex is 0 or less its the first page and we also return
		// folder/site id
		if (startIndexString == null || startIndexString.equals("") || startIndexString.equals("0")) {
			StoreRef storeRef = new StoreRef(StoreRef.PROTOCOL_WORKSPACE, "SpacesStore");
			ResultSet rsRootFolder = _searchService.query(storeRef, SearchService.LANGUAGE_LUCENE,
					"PATH:\"" + path + "\"");

			try {
				if (rsRootFolder.length() >= 0) {

					NodeRef nodeRefRootFolder = rsRootFolder.getNodeRef(0);
					NodeEntity n = new NodeEntity();
					n.setUuid((String) nodeRefRootFolder.getId());

					if (path.contains("cm:documentLibrary")) {
						n.setTypeName("cm:folder");
					} else {
						n.setTypeName("st:site");
					}

					reindexnodes.add(n);
				}

			} finally {
				rsRootFolder.close();
			}
		}

		model.put("reindexnodes", reindexnodes);
		return model;
	}

	private NamespaceService namespaceService;
	private QNameDAO qnameDao;
	private IndexingDaoImpl indexingService;
	private NodeDAO nodeDao;

	private String propertiesUrlTemplate;
	private String personPropertiesUrlTemplate;
	private String trrrPropertiesUrlTemplate;

	private int maxNodesPerAcl = 1000;
	private int maxNodesPerTxns = 1000;
	private int maxNodesPerOtherTxns = 1000;

	private final String wiki = "cm:wiki";
	private final String blog = "cm:blog";
	private final String discussion = "cm:discussion";
	private final String documentLibrary = "cm:documentLibrary";
	private final String content = "cm:content";
	private final String published = "cm:corporate_information_collection";


	public String getTrrrPropertiesUrlTemplate() {
		return trrrPropertiesUrlTemplate;
	}

	public void setTrrrPropertiesUrlTemplate(String trrrPropertiesUrlTemplate) {
		this.trrrPropertiesUrlTemplate = trrrPropertiesUrlTemplate;
	}
	public String getPersonPropertiesUrlTemplate() {
		return personPropertiesUrlTemplate;
	}

	public void setPersonPropertiesUrlTemplate(String personPropertiesUrlTemplate) {
		this.personPropertiesUrlTemplate = personPropertiesUrlTemplate;
	}
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
}
