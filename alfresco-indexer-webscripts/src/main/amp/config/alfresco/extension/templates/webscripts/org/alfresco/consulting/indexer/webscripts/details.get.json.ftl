<#escape x as jsonUtils.encodeJSONString(x)>
{
  "readableAuthorities" : [
    <#list readableAuthorities as readableAuthority>
      "${readableAuthority}"
      <#if readableAuthority_has_next>,</#if>
    </#list>
  ],
  "path" : "${path}",
  <#if shareUrlPath??>
    "shareUrlPath" : "${urlPrefix + shareUrlPath}",
  </#if>
  <#if contentUrlPath??>
    "contentUrlPath" : "${contentUrlPrefix + contentUrlPath}",
  </#if>
  <#if thumbnailUrlPath??>
    "thumbnailUrlPath" : "${urlPrefix + thumbnailUrlPath}",
  </#if>
 <#if contentDownloadUrl??>
    "contentDownloadUrl" : "${urlPrefix + contentDownloadUrl}",
</#if>
<#if previewUrlPath??>
    "previewUrlPath" : "${urlPrefix + previewUrlPath}",
</#if>
  <#if folderContentPath??>
    "folderContentPath" : "${folderContentPath}",
  </#if>
<#if fileTypeIconUrl??>
    "fileTypeIconUrl" : "${urlPrefix + fileTypeIconUrl}",
</#if>
  <#if parentNodeRef??>
  "parent": "${parentNodeRef}",
  </#if>
 
<#if site??>
  	"site": {
       	<#if site["cm:name"]??>
    		"cm:name": "${site["cm:name"]}",
      	</#if>
     	<#if site["cm:title"]??>
    		"cm:title": "${site["cm:title"]}",
    	</#if>
      	<#if site["dashboardUrlPath"]??>
    		"dashboardUrlPath": "${urlPrefix + site["dashboardUrlPath"]}"
     	</#if>
  },
  </#if>

  <#assign propNames = properties?keys>
  "aspects" : [
    <#list aspects as aspect>
    "${aspect}"
    <#if aspect_has_next>,</#if>
  </#list>
  ],
  "properties" : [
    <#list propNames as propName>
      {
        <#assign propPair=properties[propName] >
        "name" : "${propName}",
        "type" : "${propPair.first}",
        "value" : "${propPair.second}"
      }
      <#if propName_has_next>,</#if>
    </#list>
  ],
  "tags": [
    <#list tags as tag>
      "${tag}"<#if tag_has_next>,</#if>
    </#list>
  ],
  "comments": [
    <#list comments as comment>
    "${comment?replace("<(.|\n)*?>",  "", "r")}"<#if comment_has_next>,</#if>
    </#list>
  ],
  "likes": ${likes?c}
  
  <#if breadCrumbs??>
  ,"BreadCrumbs": [
    <#list breadCrumbs as breadCrumb>
      {
        "BreadCrumbLabel" : "${breadCrumb.label}",
        "BreadCrumbUrl" : "${breadCrumb.url}"
      }
      <#if breadCrumb_has_next>,</#if>
    </#list>
  ]
  </#if>
}
</#escape>