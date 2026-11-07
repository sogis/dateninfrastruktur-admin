@import java.util.Map
@import ch.so.agi.dateninfrastruktur.admin.web.DatasetLabels
@import ch.so.agi.dateninfrastruktur.admin.web.DatasetMetadataForm
@param DatasetMetadataForm metadata
@param Map<String, String> errors

<div class="form-field">
    <label for="title">Titel</label>
    <input id="title" name="title" type="text" required maxlength="255" value="${metadata.title()}"
           aria-invalid="${errors.containsKey("title") ? "true" : null}"
           aria-describedby="${errors.containsKey("title") ? "title-error" : null}">
    @template.fragment.field-error(id = "title-error", message = errors.get("title"))
</div>
<div class="form-field">
    <label for="description">Beschreibung</label>
    <textarea id="description" name="description" required rows="6" maxlength="100000"
              aria-invalid="${errors.containsKey("description") ? "true" : null}"
              aria-describedby="${errors.containsKey("description") ? "description-error" : null}">
${metadata.description()}</textarea>
    @template.fragment.field-error(id = "description-error", message = errors.get("description"))
</div>
<div class="form-field">
    <label for="contactName">Name des Kontakts <span class="field-optional">(optional)</span></label>
    <input id="contactName" name="contactName" type="text" maxlength="255" value="${metadata.contactName()}"
           aria-invalid="${errors.containsKey("contactName") ? "true" : null}"
           aria-describedby="${errors.containsKey("contactName") ? "contactName-error" : null}">
    @template.fragment.field-error(id = "contactName-error", message = errors.get("contactName"))
</div>
<div class="form-field">
    <label for="contactEmail">E-Mail des Kontakts</label>
    <input id="contactEmail" name="contactEmail" type="email" required maxlength="1016" value="${metadata.contactEmail()}"
           aria-invalid="${errors.containsKey("contactEmail") ? "true" : null}"
           aria-describedby="${errors.containsKey("contactEmail") ? "contactEmail-error" : null}">
    @template.fragment.field-error(id = "contactEmail-error", message = errors.get("contactEmail"))
</div>
<fieldset class="theme-choices" id="themes" tabindex="-1"
          aria-invalid="${errors.containsKey("themes") ? "true" : null}"
          aria-describedby="${errors.containsKey("themes") ? "themes-help themes-error" : "themes-help"}">
    <legend>Themen <span class="field-optional">(optional)</span></legend>
    <p class="field-help" id="themes-help">Sie können mehrere Themen auswählen.</p>
    <div class="theme-options">
        @for(var theme : DatasetLabels.THEMES)
            <label class="theme-option">
                <input type="checkbox" name="themes" value="${theme.code()}" checked="${metadata.themes().contains(theme.code())}">
                <span>${theme.label()}</span>
            </label>
        @endfor
    </div>
    @template.fragment.field-error(id = "themes-error", message = errors.get("themes"))
</fieldset>
<div class="form-field">
    <label for="keywords">Schlagwörter <span class="field-optional">(optional)</span></label>
    <textarea id="keywords" name="keywords" rows="4"
              aria-invalid="${errors.containsKey("keywords") ? "true" : null}"
              aria-describedby="${errors.containsKey("keywords") ? "keywords-help keywords-error" : "keywords-help"}">
${metadata.keywords()}</textarea>
    <p class="field-help" id="keywords-help">Ein Schlagwort pro Zeile, bis zu 50 Schlagwörter.</p>
    @template.fragment.field-error(id = "keywords-error", message = errors.get("keywords"))
</div>
