@import ch.so.agi.dateninfrastruktur.admin.web.UiPage
@import org.springframework.security.web.csrf.CsrfToken
@param UiPage page
@param CsrfToken csrf
<!doctype html>
<html lang="de">
<head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>${page.title()} · Dateninfrastruktur</title><link rel="stylesheet" href="/assets/management.css"><script src="/assets/htmx.min.js" defer></script></head>
<body>
<header><a href="/management">Dateninfrastruktur</a><span>Verwaltung &amp; Datenzugang</span></header>
<nav aria-label="Hauptnavigation"><a href="/management">Datensätze</a><a href="/management/datasets/new">Neuer Datensatz</a><a href="/management/requests">Offene Anfragen</a><a href="/access/me">Meine Zugänge</a><a href="/">Öffentlicher Katalog</a></nav>
<main><h1>${page.title()}</h1><p class="intro">${page.introduction()}</p>
@if(page.title().equals("Freigaben verwalten"))
<form method="get"><label>Bekannte Identität suchen<input type="search" name="search" minlength="2" maxlength="100" required></label><button type="submit">Suchen</button></form>
@endif
@for(var entry : page.entries())
<article>
@if(entry.href() != null)
<h2><a href="${entry.href()}">${entry.title()}</a></h2>
@else
<h2>${entry.title()}</h2>
@endif
<dl>
@for(var value : entry.details().entrySet())
<dt>${UiPage.label(value.getKey())}</dt><dd>
@if(value.getKey().equals("old_values") || value.getKey().equals("new_values"))
<details><summary>Werte anzeigen</summary>${UiPage.display(value.getKey(), value.getValue())}</details>
@else
${UiPage.display(value.getKey(), value.getValue())}
@endif
</dd>
@endfor
</dl>
@for(var form : entry.forms())
@template.form(form=form, csrf=csrf)
@endfor
</article>
@endfor
@for(var form : page.forms())
@template.form(form=form, csrf=csrf)
@endfor
</main><footer>Kanton Solothurn · Dateninfrastruktur</footer>
</body></html>
