<%@ taglib uri="http://java.sun.com/jsp/jstl/core" prefix="c" %>
<%@ taglib uri="http://rhn.redhat.com/rhn" prefix="rhn" %>
<%@ taglib uri="http://struts.apache.org/tags-bean" prefix="bean" %>
<%@ taglib uri="http://struts.apache.org/tags-html" prefix="html" %>
<%@ taglib uri="http://rhn.redhat.com/tags/list" prefix="rl" %>


<html:html >
<body>
<%@ include file="/WEB-INF/pages/common/fragments/package/package_header.jspf" %>

<h2>
<bean:message key="targetsystems.jsp.title"/>
</h2>

<c:if test="${requestScope.isPtfPackage}">
  <p><bean:message key="targetsystems.jsp.description_ptf_package"/></p>
</c:if>

<c:if test="${not requestScope.isPtfPackage}">
  <p><bean:message key="targetsystems.jsp.description"/></p>

  <div>

  <rl:listset name="systemSet" legend="system">
  <rhn:csrf />
    <c:set var="noAddToSsm" value="1" />
    <%@ include file="/WEB-INF/pages/common/fragments/systems/system_listdisplay.jspf" %>
      <rhn:submitted/>
      <div class="form-horizontal">
          <div class="form-group">
              <div class="col-md-12">
                  <input type="submit" class="btn btn-primary" name="dispatch" value='<bean:message key="targetsystems.jsp.installpackage"/>'/>
              </div>
          </div>
      </div>

  </rl:listset>

  </div>
</c:if>

</body>
</html:html>
