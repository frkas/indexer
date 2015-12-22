package org.alfresco.consulting.indexer.webscripts;

import org.alfresco.query.PagingRequest;
import org.alfresco.query.PagingResults;
import org.alfresco.service.cmr.security.AuthorityService;
import org.alfresco.service.cmr.security.AuthorityType;
import org.alfresco.service.cmr.security.PersonService;
import org.alfresco.service.namespace.QName;
import org.alfresco.util.Pair;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.cxf.common.util.StringUtils;
import org.springframework.extensions.webscripts.Cache;
import org.springframework.extensions.webscripts.DeclarativeWebScript;
import org.springframework.extensions.webscripts.Status;
import org.springframework.extensions.webscripts.WebScriptRequest;

import com.sun.tools.xjc.model.Model;

import java.util.*;

/**
 * Given a username, renders out the list of authorities (users and groups) it belongs to
 *
 * Please check src/main/amp/config/alfresco/extension/templates/webscripts/com/findwise/alfresco/authresolve.get.desc.xml
 * to know more about the RestFul interface to invoke the WebScript
 *
 * List of pending activities (or TODOs)
 * - Using JSON libraries (or StringBuffer), render out the payload without passing through FreeMarker template
 */
public class AuthorityResolverWebScript extends DeclarativeWebScript {

	protected static final Log logger = LogFactory.getLog(AuthorityResolverWebScript.class);

	@Override
	protected Map<String, Object> executeImpl(WebScriptRequest req, Status status, Cache cache) {
		
		String startIndexString = req.getParameter("startIndex");
		String toIndexString = req.getParameter("toIndex");
		
		//Default values
		int startIndex = 0;
		int toIndex = 10;
		
		if(startIndexString != null && !startIndexString.equals("")){
			startIndex = Integer.parseInt(startIndexString);			
		}
		if(toIndexString != null && !toIndexString.equals("")){
			toIndex = Integer.parseInt(toIndexString);			
		}

		Map<String,Set<String>> authoritiesPerUser = new HashMap<String,Set<String>>();
		Set<String> usersToParse = new HashSet<String>();

		//Parsing parameters passed from the WebScript invocation
		Map<String, String> templateArgs = req.getServiceMatch().getTemplateVars();
		String username = templateArgs.get("username");
		
		Map<String, Object> model = new HashMap<String, Object>(1, 1.0f);

		if (StringUtils.isEmpty(username)) {
			PagingRequest pagingRequest = new PagingRequest(startIndex,toIndex - startIndex +1);
			PagingResults<PersonService.PersonInfo> people =
					this.personService.getPeople(
							"",
							new ArrayList<QName>(),
							new ArrayList<Pair<QName, Boolean>>(),
							pagingRequest);
			
			int noOfReturnedUSers = 0;
			for(PersonService.PersonInfo personInfo : people.getPage()) {
				usersToParse.add(personInfo.getUserName());
				
				noOfReturnedUSers++;
			}
		} else {
			usersToParse.add(username);
		}

		for(String user : usersToParse) {
			Set<String> userAuth = authorityService.getAuthoritiesForUser(user);
			authoritiesPerUser.put(user,userAuth);
		}

		model.put("authoritiesPerUser",authoritiesPerUser);
		return model;
	}

	private AuthorityService authorityService;
	public void setAuthorityService(AuthorityService authorityService) {
		this.authorityService = authorityService;
	}

	private PersonService personService;
	public void setPersonService(PersonService personService) {
		this.personService = personService;
	}

}