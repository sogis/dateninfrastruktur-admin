@import ch.so.agi.dateninfrastruktur.admin.web.UiPage.Form
@import ch.so.agi.dateninfrastruktur.admin.web.UiPage
@import org.springframework.security.web.csrf.CsrfToken
@param Form form
@param CsrfToken csrf
<form method="post" action="${form.action()}">
<h3>${form.title()}</h3>
<input type="hidden" name="commandId" value="${form.commandId().toString()}">
<input type="hidden" name="${csrf.getParameterName()}" value="${csrf.getToken()}">
@for(var field : form.fields())
@if(field.type().equals("hidden"))
<input type="hidden" name="${field.name()}" value="${field.value()}">
@else
<label>${field.label()}
@if(field.type().equals("textarea"))
<textarea name="${field.name()}" rows="6" maxlength="100000">${field.value()}</textarea>
@elseif(field.type().equals("checkbox"))
<input type="checkbox" name="${field.name()}" value="true" checked="${field.value().equals("true")}">
@elseif(field.type().equals("select"))
<select name="${field.name()}">
@for(var option : field.options())
<option value="${option}" selected="${option.equals(field.value())}">${UiPage.display(field.name().equals("level") ? "access" : field.name(), option)}</option>
@endfor
</select>
@else
<input type="${field.type()}" name="${field.name()}" value="${field.value()}" maxlength="4000">
@endif
</label>
@endif
@endfor
<button type="submit">${form.button()}</button>
</form>
