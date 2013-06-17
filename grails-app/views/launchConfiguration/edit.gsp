<%--

    Copyright 2012 Netflix, Inc.

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

        http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.

--%>
<html>
<head>
  <meta http-equiv="Content-Type" content="text/html; charset=UTF-8"/>
  <meta name="layout" content="main"/>
  <title>Edit Launch Configuration</title>
</head>
<body>
  <div class="body">
    <h1>Edit Launch Configuration</h1>
    <g:if test="${flash.message}">
      <div class="message">${flash.message}</div>
    </g:if>
    <g:hasErrors bean="${lc}">
      <div class="errors">
        <g:renderErrors bean="${lc}" as="list"/>
      </div>
    </g:hasErrors>
    <g:form method="post">
      <input type="hidden" id="name" name="name" value="${lc.launchConfigurationName}"/>
      <div class="dialog">
        <table>
          <tbody>
          <tr class="prop" title="Changing the name requires a delete and re-create. This will fail if there are instances running.">
            <td class="name">Name:</td>
            <td>${lc.launchConfigurationName}</td>
          </tr>

          %{-- Some Amazon APIs expose the user data, others hide it. Don't show blank user data if doing so might be confusing. --}%
          <g:if test="${params.userData}">
            <tr class="advanced">
              <td colspan="2">
                <h2>User Data</h2>
              </td>
            </tr>
            <tr class="prop">
              <td class="value" colspan="2">
                <g:textArea class="resizable" id="userData" name="userData" value="${params.userData}" rows="20" cols="120" onkeydown="javascript:event.stopPropagation();"/>
              </td>
            </tr>
          </g:if>

          <g:render template="/launchConfiguration/launchConfigOptions"/>
          </tbody>
        </table>
      </div>

      <div class="buttons">
        <g:buttonSubmit class="save" value="Update Launch Configuration" action="update"/>
      </div>
    </g:form>
  </div>
</body>
</html>
