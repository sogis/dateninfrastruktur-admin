@import ch.so.agi.dateninfrastruktur.admin.web.NavigationSection
@import ch.so.agi.dateninfrastruktur.admin.web.UiPage
@import org.springframework.security.web.csrf.CsrfToken
@param UiPage page
@param CsrfToken csrf
@param NavigationSection section
@param boolean identitySearch = false
@template.layout.application(title = page.title(), section = section, content = @`
    <p class="intro">${page.introduction()}</p>
    @if(identitySearch)
        <form class="management-form" method="get">
            <label class="form-field">Bekannte Identität suchen
                <input type="search" name="search" minlength="2" maxlength="100" required>
            </label>
            <button class="button" type="submit">Suchen</button>
        </form>
    @endif
    @for(var entry : page.entries())
        <article class="record">
            @if(entry.href() != null)
                <h2><a href="${entry.href()}">${entry.title()}</a></h2>
            @else
                <h2>${entry.title()}</h2>
            @endif
            @if(!entry.details().isEmpty())
                <dl class="record-details">
                    @for(var value : entry.details().entrySet())
                        <dt>${UiPage.label(value.getKey())}</dt>
                        <dd>${UiPage.display(value.getKey(), value.getValue())}</dd>
                    @endfor
                </dl>
            @endif
            @for(var form : entry.forms())
                @template.form(form = form, csrf = csrf, nested = true)
            @endfor
        </article>
    @endfor
    @for(var form : page.forms())
        @template.form(form = form, csrf = csrf)
    @endfor
`)
