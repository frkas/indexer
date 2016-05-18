{
<#if nodes??>
  "docs" : [
    <#list nodes as node>
      {
        <#assign qname=QName.createQName(node.getTypeNamespace(),node.getTypeName()) >
        <#assign suffix="/"+storeProtocol+"/"+storeId+"/"+node.uuid >
        <#if node.userName??>"propertiesUrl" : "${personPropertiesUrlTemplate + node.userName}",<#elseif propertiesUrlTemplate??>"propertiesUrl" : "${propertiesUrlTemplate + suffix}",</#if>
        "uuid" : "${node.uuid}",
        "type" : "${qname.toPrefixString(nsResolver)}",
        "deleted" : ${node.isDeleted()?string}
      }
      <#if node_has_next>,</#if>
    </#list>
  ],
  <#if lastTxnId??>
    "last_txn_id" : "${lastTxnId?c}",
  </#if>
    <#if lastOtherTxnId??>
    "last_other_txn_id" : "${lastOtherTxnId?c}",
  </#if>
  <#if lastAclChangesetId??>
    "last_acl_changeset_id" : "${lastAclChangesetId?c}",
  </#if>
  "store_id" : "${storeId}",
  "store_protocol" : "${storeProtocol}"
</#if>
<#if reindexnodes??>
<@compress single_line=true>
"docs" : [
    <#list reindexnodes as node>
      {
      <#assign suffix="/"+storeProtocol+"/"+storeId+"/"+node.uuid >
        <#if node.userName??>"propertiesUrl" : "${personPropertiesUrlTemplate + node.userName}",<#elseif propertiesUrlTemplate??>"propertiesUrl" : "${propertiesUrlTemplate + suffix}",</#if>
        "uuid" : "${node.uuid}",
        "type" : "${node.typeName}"
      }
      <#if node_has_next>,</#if>
    </#list>
    ]
</@compress>
</#if>  
}