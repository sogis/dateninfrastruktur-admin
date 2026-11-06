@import ch.so.agi.dateninfrastruktur.admin.web.NavigationSection
@import gg.jte.Content
@param String title
@param Content content
@param NavigationSection section = null
<!doctype html>
<html lang="de-CH">
<head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>${title} · Dateninfrastruktur</title>
    <link rel="stylesheet" href="/assets/management.css">
</head>
<body>
<a class="skip-link" href="#main-content">Zum Inhalt</a>
<header class="site-header">
    <div class="site-header-inner">
        <a class="site-brand" href="/management">Dateninfrastruktur</a>
        <span>Verwaltung &amp; Datenzugang</span>
    </div>
    <nav class="site-nav" aria-label="Hauptnavigation">
        @for(var item : NavigationSection.values())
            <a href="${item.href()}" aria-current="${item == section ? "page" : null}">${item.label()}</a>
        @endfor
        <a href="/">Öffentlicher Katalog</a>
    </nav>
</header>
<main class="main-content" id="main-content" tabindex="-1">
    <h1>${title}</h1>
    ${content}
</main>
<footer class="site-footer">Dateninfrastruktur</footer>
</body>
</html>
