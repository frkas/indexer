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
    "shareUrlPath" : "${shareUrlPrefix + shareUrlPath}",
  </#if>
  <#if contentUrlPath??>
    "contentUrlPath" : "${contentUrlPrefix + contentUrlPath}",
  </#if>
  <#if thumbnailUrlPath??>
    "thumbnailUrlPath" : "${thumbnailUrlPrefix + thumbnailUrlPath}",
  </#if>
 <#if contentDownloadUrl??>
    "contentDownloadUrl" : "${previewUrlPrefix + contentDownloadUrl}",
</#if>
<#if previewUrlPath??>
    "previewUrlPath" : "${shareUrlPrefix + previewUrlPath}",
</#if>
<#if fileTypeIconUrl??>
    "fileTypeIconUrl" : "${shareUrlPrefix + fileTypeIconUrl}",
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
    		"dashboardUrlPath": "${shareUrlPrefix + site["dashboardUrlPath"]}"
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