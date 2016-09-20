package org.alfresco.consulting.indexer.webscripts;

import java.io.Serializable;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

//import org.alfresco.jlan.client.info.FileInfo;
import org.alfresco.model.ContentModel;
import org.alfresco.model.ForumModel;
import org.alfresco.repo.domain.node.NodeDAO;
import org.alfresco.repo.domain.permissions.Acl;
import org.alfresco.repo.domain.permissions.AclDAO;
import org.alfresco.repo.security.permissions.AccessControlEntry;
import org.alfresco.service.cmr.dictionary.DictionaryService;
import org.alfresco.service.cmr.model.FileFolderService;
import org.alfresco.service.cmr.model.FileInfo;
import org.alfresco.service.cmr.rating.RatingService;
import org.alfresco.service.cmr.repository.ChildAssociationRef;
import org.alfresco.service.cmr.repository.ContentData;
import org.alfresco.service.cmr.repository.ContentReader;
import org.alfresco.service.cmr.repository.ContentService;
import org.alfresco.service.cmr.repository.MimetypeService;
import org.alfresco.service.cmr.repository.NodeRef;
import org.alfresco.service.cmr.repository.NodeService;
import org.alfresco.service.cmr.repository.Path;
import org.alfresco.service.cmr.security.AccessStatus;
import org.alfresco.service.cmr.security.PermissionService;
import org.alfresco.service.cmr.site.SiteInfo;
import org.alfresco.service.cmr.site.SiteService;
import org.alfresco.service.cmr.tagging.TaggingService;
import org.alfresco.service.namespace.NamespaceService;
import org.alfresco.service.namespace.QName;
import org.alfresco.util.Pair;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang.StringEscapeUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.json.simple.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.extensions.webscripts.Cache;
import org.springframework.extensions.webscripts.DeclarativeWebScript;
import org.springframework.extensions.webscripts.Status;
import org.springframework.extensions.webscripts.WebScriptRequest;

import com.google.gdata.util.common.base.StringUtil;
import com.scania.alfresco.drs.repo.model.DrsModel;

/**
 * Given a nodeRef, renders out all data about a node (except binary content): -
 * Node metadata - Node ACLs
 * 
 * Please check
 * 
 * src/main/amp/config/alfresco/extension/templates/webscripts/com/findwise/
 * alfresco/details.get.desc.xml to know more about the RestFul interface to
 * invoke the WebScript
 * 
 * List of pending activities (or TODOs) - Refactor recursive getAllAcls (direct
 * recursion) . Evaluate the possibility to write a SQL statement for that
 * 
 * - Move private/static logic into the IndexingService (see notes on
 * 
 * NodeChangesWebScript) - Move the following methods (and related SQL
 * statements) into
 * 
 * IndexingDaoImpl
 * 
 * -- nodeService.getProperties -- nodeService.getAspects --
 * nodeDao.getNodeAclId -- solrDao.getNodesByAclChangesetId --
 * nodeService.getType and dictionaryService.isSubClass (should be merged into
 * one)
 *
 * - Using JSON libraries (or StringBuffer), render out the payload without
 * 
 * passing through FreeMarker template
 */

public class NodeDetailsWebScript extends DeclarativeWebScript {

	@Autowired
	@Qualifier("global-properties")
	protected Properties _globalProperties;

	@Autowired
	@Qualifier("FileFolderService")
	protected FileFolderService _fileFolderService;

	protected static final Log logger = LogFactory.getLog(NodeDetailsWebScript.class);
	protected static final SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
	
	private String spaceCorrectEncode = "%2520";

	@Override
	protected Map<String, Object> executeImpl(WebScriptRequest req, Status status, Cache cache) {

		final List<String> readableAuthorities = new ArrayList<String>();

		// Parsing parameters passed from the WebScript invocation
		Map<String, String> templateArgs = req.getServiceMatch().getTemplateVars();

		String storeId = templateArgs.get("storeId");
		String storeProtocol = templateArgs.get("storeProtocol");
		String uuid = templateArgs.get("uuid");

		NodeRef nodeRef = new NodeRef(storeProtocol, storeId, uuid);

		// Processing properties
		Map<QName, Serializable> propertyMap = nodeService.getProperties(nodeRef);
		Map<String, Pair<String, String>> properties = toStringMap(propertyMap);

		// Processing aspects
		Set<QName> aspectsSet = nodeService.getAspects(nodeRef);
		Set<String> aspects = toStringSet(aspectsSet);

		// Get the node ACL Id
		Long dbId = (Long) propertyMap.get(ContentModel.PROP_NODE_DBID);
		Long nodeAclId = nodeDao.getNodeAclId(dbId);

		// Get also the inherited ones
		List<Acl> acls = getAllAcls(nodeAclId);

		// @TODO - avoid reverse by implementing direct recursion
		Collections.reverse(acls);

		// Getting path and siteName
		Path pathObj = nodeService.getPath(nodeRef);
		String path = pathObj.toPrefixString(namespaceService);
		String siteName = getSiteName(pathObj);
		String fileName = getFileName(pathObj);
		String iconName = getIconName(nodeRef);

		String url = getShareUrl();

		// Walk through ACLs and related ACEs, rendering out authority names
		// having a granted permission on the node
		for (Acl acl : acls) {
			List<AccessControlEntry> aces = aclDao.getAccessControlList(acl.getId()).getEntries();

			for (AccessControlEntry ace : aces) {
				if (ace.getAccessStatus().equals(AccessStatus.ALLOWED)) {
					// ReadPermissions should be skipped as otherwise the
					// ALLOWED will include GROUP_EVERYONE
					if (ace.getPermission().getName().equals(PermissionService.READ_PERMISSIONS)) {
						continue;
					}
					if (!readableAuthorities.contains(ace.getAuthority())) {
						readableAuthorities.add(ace.getAuthority());
					}
				}
			}
		}

		Map<String, Object> model = new HashMap<String, Object>(1, 1.0f);

		model.put("nsResolver", namespaceService);
		model.put("readableAuthorities", readableAuthorities);
		model.put("properties", properties);
		model.put("aspects", aspects);
		model.put("path", path);
		model.put("contentUrlPrefix", contentUrlPrefix);
		model.put("urlPrefix", url);

		List<BreadCrumb> breadCrumbs = getBreadCrumbs(url, nodeRef);
		model.put("breadCrumbs", breadCrumbs);
		
		// add the parent nodeRef if there's one
		ChildAssociationRef primaryParent = nodeService.getPrimaryParent(nodeRef);
		if (primaryParent != null) {
			model.put("parentNodeRef", primaryParent.getParentRef().toString());
		}

		// if the node is in a site, add info about the site
		SiteInfo siteInfo = siteService.getSite(nodeRef);
		if (siteInfo != null) {
			Map<String, String> site = new HashMap<>();
			site.put("cm:name", siteInfo.getShortName());
			site.put("cm:title", siteInfo.getTitle());
			site.put("dashboardUrlPath", String.format("/page/site/%s/dashboard", siteInfo.getShortName()));
			model.put("site", site);
		}

		// Calculating the contentUrlPath and adding it only if the contentType
		// is child of cm:content
		boolean isContentAware = isContentAware(nodeRef);
		if (isContentAware) {
			String contentUrlPath = String.format("/api/node/%s/%s/%s/content", storeProtocol, storeId, uuid);
			model.put("contentUrlPath", contentUrlPath);
		}

		//If its a folder we will return the folderview(Just like a breadcrumb)
		FileInfo fileInfo =_fileFolderService.getFileInfo(nodeRef);
		if(fileInfo.isFolder()){

			String folderContentPath = getFolderContentPath(breadCrumbs, propertyMap.get(ContentModel.PROP_NAME).toString());
			
			//Remove first part of url to adjust to the same format as shareUrlPath
			String shareUrlPathForFolder = folderContentPath.substring(url.length());
			
			model.put("shareUrlPath", shareUrlPathForFolder);

		// Rendering out the (relative) URL path to Alfresco Share
		}else if (!StringUtil.isEmpty(siteName)) {
			String shareUrlPath = String.format("/page/site/%s/document-details?nodeRef=%s", siteName,
					nodeRef.toString());
			model.put("shareUrlPath", shareUrlPath);
		}

		String thumbnailUrlPath = String.format(
				"/proxy/alfresco/api/node/%s/%s/%s/content/thumbnails/doclib?c=queue&ph=true&lastModified=1",
				storeProtocol, storeId, uuid);
		model.put("thumbnailUrlPath", thumbnailUrlPath);

		if (!StringUtil.isEmpty(fileName)) {
			String contentDownloadUrl = String.format("/proxy/alfresco/api/node/content/%s/%s/%s/%s?a=true",
					storeProtocol, storeId, uuid, fileName);
			model.put("contentDownloadUrl", contentDownloadUrl);
		}

		if (!StringUtil.isEmpty(siteName)) {
			String previewUrlPath = String.format("/page/site/%s/document-details?nodeRef=%s&previewOnly=true",
					siteName, nodeRef.toString());
			model.put("previewUrlPath", previewUrlPath);
		}
		
		else if(nodeService.hasAspect(nodeRef, DrsModel.ASPECT_RELEASED)){
	
			String previewUrlPath = String.format("/page/document-details?nodeRef=%s&previewOnly=true",
					nodeRef.toString());
			model.put("previewUrlPath", previewUrlPath);
		}
		
		if (!StringUtil.isEmpty(iconName)) {
			String fileTypeIconUrl = String.format("/res/components/images/filetypes/%s", iconName);
			model.put("fileTypeIconUrl", fileTypeIconUrl);
		}
		model.put("tags", taggingService.getTags(nodeRef));

		int likes = ratingService.getRatingsCount(nodeRef, "likesRatingScheme");
		model.put("likes", likes);

		model.put("comments", getComments(nodeRef));

		return model;
	}

	private String getFolderContentPath(List<BreadCrumb> breadCrumbs, String folderName){
		
		//Use the last breadcrump to get the foldercontentpath
		String folderContentPath = breadCrumbs.get(breadCrumbs.size() -1).getUrl();
		
		//Fix spaceencoding
		folderName = folderName.replace(" ", spaceCorrectEncode);
		
		if(breadCrumbs.size() == 1){
			folderContentPath += "#filter=path|%2F" + folderName;
		}else if(breadCrumbs.size()>1){
			folderContentPath += "%2F" + folderName;
		}
				
		return folderContentPath;
	}
	
	/**
	 * Get the folderPath as breadcrumbs for a given document (NodeRef)
	 * 
	 * @param firstPartOfUrl
	 *            This will be http(s)://<host>:<port>/<context>
	 * @param nodeRef
	 *            NodeRef of the document
	 * @return A list of BreadCrumb objects
	 */
	private List<BreadCrumb> getBreadCrumbs(String firstPartOfUrl, NodeRef nodeRef) {
		try {

			List<BreadCrumb> breadCrumbs = new ArrayList<>();

			String url = nodeService.getPath(nodeRef).toDisplayPath(nodeService, permissionService);
			
			// Add page to firstpartUrl
			firstPartOfUrl += "/page";

			if (url.startsWith("/Company Home/Sites/")) {
				url = url.substring("/Company Home/Sites/".length());

				firstPartOfUrl += "/site/";

				firstPartOfUrl += url.substring(0, url.indexOf('/'));

				// Add document library
				firstPartOfUrl += "/documentlibrary";

				// Remove /documentlibrary from url
				int indexOfDocumentLibrary = url.indexOf("/documentLibrary");

				url = url.substring("/documentLibrary".length() + indexOfDocumentLibrary);

				// First breadcrumb named Documents
				BreadCrumb breadCrumb = new BreadCrumb();
				breadCrumb.setLabel("Documents");
				breadCrumb.setUrl(firstPartOfUrl);
				breadCrumbs.add(breadCrumb);

			} else if (url.startsWith("/Company Home/")) {

				// Remove /company home from url
				url = url.substring("/Company Home".length());

				firstPartOfUrl += "/repository";

				// First breadcrumb named Documents
				BreadCrumb breadCrumb = new BreadCrumb();
				breadCrumb.setLabel("Documents");
				breadCrumb.setUrl(firstPartOfUrl);
				breadCrumbs.add(breadCrumb);

			} else {
				// Dont create breadcrumb
				return null;
			}

			String filterPath = "#filter=path|";

			String[] folders = url.split("/");

			// Start with 1, positions 0 will be empty
			for (int i = 1; i < folders.length; i++) {

				// filterPath += "%2F" + folders[i].replace(zeroEncode, "0");
				filterPath += "%2F" + folders[i];
				String nameWithSpaces = folders[i].replace(spaceCorrectEncode, " ");
				// nameWithSpaces = nameWithSpaces.replace(zeroEncode, "0");

				BreadCrumb breadCrumb = new BreadCrumb();
				breadCrumb.setLabel(nameWithSpaces);
				breadCrumb.setUrl(firstPartOfUrl + filterPath.replace(" ", spaceCorrectEncode));
				breadCrumbs.add(breadCrumb);
			}
			return breadCrumbs;
		} catch (Exception e) {
			// Can happen for example st:sites
			return null;
		}
	}

	private String getShareUrl() {
		StringBuffer url = new StringBuffer();

		if ("443".equals(_globalProperties.getProperty("share.port"))) {
			url.append("https://");
		} else {
			url.append("http://");
		}
		url.append(_globalProperties.getProperty("share.host"));
		url.append(":");
		url.append(_globalProperties.getProperty("share.port"));
		url.append("/");
		url.append(_globalProperties.getProperty("share.context"));

		return url.toString();
	}

	private String getSiteName(Path path) {

		// Fetching Path and preparing for rendering
		Iterator<Path.Element> pathIter = path.iterator();

		// Scan the Path to find the Alfresco Site name
		boolean siteFound = false;

		while (pathIter.hasNext()) {
			String pathElement = pathIter.next().getElementString();
			// Stripping out namespace from PathElement
			int firstChar = pathElement.lastIndexOf('}');
			if (firstChar > 0) {
				pathElement = pathElement.substring(firstChar + 1);
			}
			if (pathElement.equals("sites")) {
				siteFound = true;
			} else if (siteFound) {
				return pathElement;
			}
		}

		return null;
	}

	private String getFileName(Path path) {

		String baseName = FilenameUtils.getBaseName(path.toString());
		String extension = FilenameUtils.getExtension(path.toString());
		String fileName = baseName + "." + extension;

		if (fileName.contains("?")) {
			fileName = fileName.substring(0, fileName.indexOf("?"));
		}

		if (fileName.contains("#")) {
			fileName = fileName.substring(0, fileName.indexOf("#"));
		}

		if (fileName.contains("}")) {
			fileName = fileName.substring(fileName.indexOf("}") + 1);
		}

		return fileName;
	}

	private String getIconName(NodeRef nodeRef) {

		try {

			ContentData contentData = (ContentData) nodeService.getProperty(nodeRef, ContentModel.PROP_CONTENT);

			String nodeMimeType = contentData.getMimetype();

			String iconName = mimetypeService.getExtension(nodeMimeType) + "-file-48.png";
		
			return iconName;
			
		} catch (Exception e) {
			return null;
		}

	}

	private boolean isContentAware(NodeRef nodeRef) {

		QName contentType = nodeService.getType(nodeRef);

		return dictionaryService.isSubClass(contentType, ContentModel.TYPE_CONTENT);
	}

	private Set<String> toStringSet(Set<QName> aspectsSet) {

		Set<String> ret = new HashSet<String>();

		for (QName aspect : aspectsSet) {
			ret.add(aspect.toPrefixString(namespaceService));
		}

		return ret;
	}

	private Map<String, Pair<String, String>> toStringMap(Map<QName, Serializable> propertyMap) {

		Map<String, Pair<String, String>> ret = new HashMap<String, Pair<String, String>>(1, 1.0f);

		for (QName propertyName : propertyMap.keySet()) {

			Serializable propertyValue = propertyMap.get(propertyName);

			if (propertyValue != null) {
				String propertyType = propertyValue.getClass().getName();
				String stringValue = propertyValue.toString();

				if (propertyType.equals("java.util.Date")) {
					stringValue = sdf.format(propertyValue);
				}

				ret.put(propertyName.toPrefixString(namespaceService),
						new Pair<String, String>(propertyType, stringValue));
			}
		}
		return ret;
	}

	private List<Acl> getAllAcls(Long nodeAclId) {

		Acl acl = aclDao.getAcl(nodeAclId);

		Long parentNodeAclId = acl.getInheritsFrom();

		if (parentNodeAclId == null || !acl.getInherits()) {
			List<Acl> ret = new ArrayList<Acl>();
			ret.add(acl);
			return ret;
		} else {
			List<Acl> inheritedAcls = getAllAcls(parentNodeAclId);

			inheritedAcls.add(acl);
			return inheritedAcls;
		}

	}

	private List<String> getComments(NodeRef node) {

		List<String> comments = new ArrayList<String>();

		for (ChildAssociationRef child : nodeService.getChildAssocs(node)) {
			if (ForumModel.ASSOC_DISCUSSION.isMatch(child.getTypeQName())) {
				List<ChildAssociationRef> topics = nodeService.getChildAssocs(child.getChildRef());
				for (ChildAssociationRef topic : topics) {
					QName topicType = nodeService.getType(topic.getChildRef());

					if (ForumModel.TYPE_TOPIC.isMatch(topicType)) {
						List<ChildAssociationRef> posts = nodeService.getChildAssocs(topic.getChildRef());

						for (ChildAssociationRef post : posts) {
							NodeRef postNode = post.getChildRef();
							ContentReader reader = contentService.getReader(postNode, ContentModel.PROP_CONTENT);
							String content = reader.getContentString();
							content = StringEscapeUtils.unescapeHtml(content);
							content = JSONObject.escape(content);
							comments.add(content);
						}
					}
				}
			}
		}
		return comments;
	}

	private DictionaryService dictionaryService;
	private NamespaceService namespaceService;
	private NodeService nodeService;
	private NodeDAO nodeDao;
	private AclDAO aclDao;
	private TaggingService taggingService;
	private RatingService ratingService;
	private ContentService contentService;
	private SiteService siteService;
	private PermissionService permissionService;
	private MimetypeService mimetypeService;
	private String contentUrlPrefix;
//	private String shareUrlPrefix;
//	private String previewUrlPrefix;
//	private String thumbnailUrlPrefix;

	public MimetypeService getMimetypeService() {
		return mimetypeService;
	}

	public void setMimetypeService(MimetypeService mimetypeService) {
		this.mimetypeService = mimetypeService;
	}

	public void setPermissionService(PermissionService permissionService) {
		this.permissionService = permissionService;
	}

	public void setDictionaryService(DictionaryService dictionaryService) {
		this.dictionaryService = dictionaryService;
	}

	public void setNamespaceService(NamespaceService namespaceService) {
		this.namespaceService = namespaceService;
	}

	public void setNodeService(NodeService nodeService) {
		this.nodeService = nodeService;
	}

	public void setNodeDao(NodeDAO nodeDao) {
		this.nodeDao = nodeDao;
	}

	public void setAclDao(AclDAO aclDao) {
		this.aclDao = aclDao;
	}

	public void setTaggingService(TaggingService taggingService) {
		this.taggingService = taggingService;
	}

	public void setRatingService(RatingService ratingService) {
		this.ratingService = ratingService;
	}

	public void setContentService(ContentService contentService) {
		this.contentService = contentService;
	}

	public void setSiteService(SiteService siteService) {
		this.siteService = siteService;
	}

	public void setContentUrlPrefix(String contentUrlPrefix) {
		this.contentUrlPrefix = contentUrlPrefix;
	}
}

