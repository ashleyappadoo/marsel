from pathlib import Path
from zipfile import ZipFile, ZIP_DEFLATED
from xml.sax.saxutils import escape
from datetime import datetime, timezone

OUT = Path("docs/Marsel-Pro-Event-Executive-Summary-Business-Developer.docx")
OUT.parent.mkdir(parents=True, exist_ok=True)

def run(text, bold=False, italic=False, color=None, size=None):
    props = []
    if bold: props.append("<w:b/>")
    if italic: props.append("<w:i/>")
    if color: props.append(f'<w:color w:val="{color}"/>')
    if size: props.append(f'<w:sz w:val="{size}"/><w:szCs w:val="{size}"/>')
    preserve = ' xml:space="preserve"' if text[:1].isspace() or text[-1:].isspace() else ""
    return f'<w:r><w:rPr>{"".join(props)}</w:rPr><w:t{preserve}>{escape(text)}</w:t></w:r>'

def para(text="", style=None, before=0, after=140, align=None, keep=False, page_break=False,
         bold=False, italic=False, color=None, size=None, num=False):
    if page_break:
        return '<w:p><w:r><w:br w:type="page"/></w:r></w:p>'
    pp = []
    if style: pp.append(f'<w:pStyle w:val="{style}"/>')
    pp.append(f'<w:spacing w:before="{before}" w:after="{after}" w:line="276" w:lineRule="auto"/>')
    if align: pp.append(f'<w:jc w:val="{align}"/>')
    if keep: pp.append("<w:keepNext/>")
    if num:
        pp.append('<w:numPr><w:ilvl w:val="0"/><w:numId w:val="1"/></w:numPr>')
    return f'<w:p><w:pPr>{"".join(pp)}</w:pPr>{run(text,bold,italic,color,size)}</w:p>'

def bullet(text):
    return para(text, num=True, after=80)

def cell(text, width, header=False):
    shade = '<w:shd w:val="clear" w:color="auto" w:fill="1F2937"/>' if header else ""
    color = "FFFFFF" if header else "141F33"
    bold = header
    return (f'<w:tc><w:tcPr><w:tcW w:w="{width}" w:type="dxa"/>'
            f'<w:tcMar><w:top w:w="100" w:type="dxa"/><w:left w:w="120" w:type="dxa"/>'
            f'<w:bottom w:w="100" w:type="dxa"/><w:right w:w="120" w:type="dxa"/></w:tcMar>{shade}</w:tcPr>'
            f'{para(text, after=0, bold=bold, color=color, size=19)}</w:tc>')

def table(rows, widths):
    parts = ['<w:tbl><w:tblPr><w:tblW w:w="0" w:type="auto"/>'
             '<w:tblBorders><w:top w:val="single" w:sz="4" w:color="D9D9D9"/>'
             '<w:left w:val="single" w:sz="4" w:color="D9D9D9"/>'
             '<w:bottom w:val="single" w:sz="4" w:color="D9D9D9"/>'
             '<w:right w:val="single" w:sz="4" w:color="D9D9D9"/>'
             '<w:insideH w:val="single" w:sz="4" w:color="D9D9D9"/>'
             '<w:insideV w:val="single" w:sz="4" w:color="D9D9D9"/></w:tblBorders></w:tblPr>']
    for i,row in enumerate(rows):
        parts.append('<w:tr>')
        for value,width in zip(row,widths):
            parts.append(cell(value,width,header=(i==0)))
        parts.append('</w:tr>')
    parts.append('</w:tbl>')
    return "".join(parts)

body = []
body += [
    para("MARSEL PRO EVENT", before=520, after=80, bold=True, color="E84315", size=20),
    para("Note de synthèse du modèle professionnel", style="Title", after=120),
    para("Document destiné au Business Developer de Marsel", style="Subtitle", after=420),
    para("Version du 14 septembre 2026", italic=True, color="4C5D74", size=19, after=620),
    para("Objet du document", style="Heading1"),
    para("Cette note présente le positionnement, les clients, la proposition de valeur, le fonctionnement et le modèle économique de Marsel Pro Event. Elle traduit le cahier des charges de production en arguments directement utilisables pour identifier des partenaires, qualifier des opportunités et préparer les premières offres commerciales."),
    para("Le périmètre décrit représente la cible de production. Les capacités radio, les niveaux de service et les performances devront être qualifiés avant tout engagement contractuel.", italic=True, color="4C5D74"),
    para(page_break=True),
    para("1  Positionnement", style="Heading1"),
    para("Marsel Pro Event est une solution SaaS de préparation, de pilotage et de traçabilité des opérations de sécurité événementielle. Elle relie les signalements du public, les agents sur le terrain et le poste de commandement dans un même environnement opérationnel."),
    para("Marsel commercialise un logiciel. Les organisateurs et les entreprises de sécurité conservent la responsabilité de leurs équipes, de leurs procédures et de leurs interventions. Cette séparation permet à Marsel de développer un modèle SaaS récurrent, déployable auprès de nombreux clients sans devenir lui-même une société de sécurité."),
    para("Promesse commerciale", style="Heading2"),
    para("Marsel Pro Event connecte le public, les agents et le poste de commandement afin de transmettre plus vite les signalements, coordonner les interventions et documenter chaque événement.", bold=True, size=23),
    para("2  Les trois composantes de l’offre", style="Heading1"),
    table([
        ["Composante", "Fonction", "Valeur pour le client"],
        ["SaaS Marsel Pro Event", "Préparation, supervision, alertes, affectations, rapports et parc Relay.", "Centraliser les opérations et assurer leur traçabilité."],
        ["Application Marsel Pro", "Missions, codes rapides, messagerie, rondes, renfort et SOS agent.", "Permettre aux agents d’agir et de communiquer rapidement."],
        ["Marsel Relay Long Range", "Extension des transmissions et continuité locale sur les zones équipées.", "Renforcer la résilience du dispositif événementiel."]
    ], [2300, 3550, 3500]),
    para("L’application publique Marsel complète l’ensemble : un participant peut transmettre une alerte au dispositif Event sans compte professionnel. La version Pro réutilise le socle technique public tout en ajoutant des droits et des échanges adaptés aux opérations professionnelles.", before=160),
    para(page_break=True),
    para("3  Problèmes clients résolus", style="Heading1"),
]
for x in [
    "Des alertes reçues par plusieurs canaux et difficiles à centraliser.",
    "Une affectation des interventions souvent effectuée par téléphone ou radio, avec peu de visibilité partagée.",
    "Une difficulté à distinguer un message transmis, reçu techniquement et réellement pris en charge par un agent.",
    "Une traçabilité dispersée entre main courante, feuilles de ronde, messages et comptes rendus.",
    "Des communications fragilisées dans les lieux très fréquentés ou lorsque l’accès Internet est indisponible.",
    "Une difficulté pour le prestataire à démontrer précisément la prestation réalisée à son client."
]: body.append(bullet(x))
body += [
    para("4  Fonctionnement avant pendant et après l’événement", style="Heading1"),
    para("Avant l’événement", style="Heading2"),
    para("Le responsable configure les plans, les zones, les équipes, les horaires, les consignes, les catégories d’incidents, les niveaux de priorité, les règles d’escalade et les équipements Relay. Il vérifie que les appareils disposent de la bonne configuration avant l’ouverture."),
    para("Pendant l’événement", style="Heading2"),
    para("Le poste de commandement reçoit les signalements, les qualifie, les affecte et suit leur progression. Les agents utilisent l’app pour accepter une intervention, se déclarer en route ou sur place, demander un renfort, transmettre une observation et proposer la résolution de l’incident."),
    para("Après l’événement", style="Heading2"),
    para("Marsel consolide la chronologie, la main courante, les rondes et les indicateurs. Le responsable valide un rapport avant sa publication dans le portail du client organisateur."),
    para("5  Fonction différenciante  Les codes rapides", style="Heading1"),
    para("Le responsable prépare dans le SaaS un catalogue de messages utilisables par les agents : Assistance médicale, Renfort sécurité, Accès bloqué, Personne à accompagner, Matériel défaillant, Sur place, Mission terminée ou SOS agent."),
    para("Chaque code peut définir une priorité, une destination, une action et des informations à joindre comme la zone, l’incident ou la dernière position disponible. Le catalogue est versionné et préchargé sur les appareils pour rester compréhensible hors connexion. L’agent transmet ainsi une information structurée en quelques gestes, avec moins d’ambiguïté qu’un message libre."),
    para(page_break=True),
    para("6  Clients et interlocuteurs prioritaires", style="Heading1"),
    para("Entreprises de sécurité événementielle", style="Heading2"),
    para("Elles peuvent exploiter Marsel sur plusieurs contrats, renforcer la valeur de leur prestation et partager des rapports approuvés avec leurs clients. Elles constituent aussi un canal potentiel de prescription ou de revente."),
    para("Organisateurs d’événements", style="Heading2"),
    para("Festivals, concerts, salons, événements sportifs, manifestations culturelles et grands rassemblements ayant besoin d’une vision centralisée du dispositif."),
    para("Exploitants de lieux", style="Heading2"),
    para("Stades, salles, parcs, centres de congrès et sites accueillant régulièrement des événements. Ils peuvent réutiliser les plans, zones, équipements et processus d’une exploitation à l’autre."),
    para("Les interlocuteurs à identifier sont le décideur économique, le responsable sécurité, le responsable d’exploitation, le prestataire de sécurité et, selon le niveau d’intégration, la direction informatique."),
    para("7  Valeur apportée", style="Heading1"),
]
for x in [
    "Centraliser les signalements, les équipes, les interventions et les comptes rendus.",
    "Réduire le temps nécessaire pour transmettre et qualifier une information grâce aux codes rapides.",
    "Donner au poste de commandement une vue partagée de la situation et des responsabilités.",
    "Maintenir une capacité opérationnelle locale sur les événements équipés lors d’une coupure Internet.",
    "Constituer une chronologie exploitable pour le retour d’expérience et le rapport client.",
    "Permettre au prestataire de rendre sa prestation plus visible et plus facile à documenter."
]: body.append(bullet(x))
body += [
    para("Les bénéfices devront être démontrés par des indicateurs mesurés : adoption par les agents, délais de transmission et de prise en charge, qualité de la traçabilité, disponibilité du dispositif, effort de déploiement et intention de renouvellement.", italic=True, color="4C5D74"),
    para(page_break=True),
    para("8  Modèle économique", style="Heading1"),
    para("Les revenus reposent sur une licence événementielle ou un abonnement professionnel. La tarification peut intégrer le nombre d’événements couverts, leur durée, les agents simultanément en service, les fonctions utilisées, le stockage et les intégrations.")
]
for x in [
    "Licence Event pour un événement temporaire.",
    "Abonnement Entreprise pour un prestataire ou organisateur exploitant plusieurs événements.",
    "Vente ou location des équipements Relay Long Range.",
    "Options liées au multisite, aux intégrations, au stockage ou à l’accompagnement technique.",
    "Commission d’apport ou de revente sur le revenu logiciel encaissé pour les partenaires éligibles."
]: body.append(bullet(x))
body += [
    para("La rémunération des partenaires ne dépend pas du nombre de SOS. Les services humains, les agents supplémentaires et l’organisation des interventions restent facturés par le prestataire de sécurité dans son propre contrat."),
    para("9  Positionnement concurrentiel à défendre", style="Heading1"),
    para("La différenciation de Marsel repose sur la continuité entre le public, les agents et le poste de commandement. La solution couvre le signalement, l’affectation, l’intervention, la communication et le rapport dans un même produit. Relay Long Range et le fonctionnement local renforcent cette continuité dans les configurations qualifiées."),
    para("Les formulations commerciales doivent rester précises : la portée radio dépend du matériel, du site et de la topologie ; un accusé technique ne signifie pas qu’un agent intervient ; Marsel ne remplace ni les procédures de sécurité ni les services publics d’urgence.", italic=True, color="4C5D74"),
    para("10  Mission du Business Developer", style="Heading1"),
]
for x in [
    "Identifier des événements et prestataires disposant d’un problème réel de coordination ou de couverture.",
    "Cartographier le décideur, le responsable opérationnel, le prestataire et les contraintes techniques.",
    "Qualifier les volumes : participants, agents, zones, durée, incidents, appareils et besoin de continuité.",
    "Documenter les outils et procédures actuels ainsi que les coûts et irritants associés.",
    "Obtenir un engagement pour un déploiement qualifié avec critères de réussite et perspective de renouvellement.",
    "Recueillir le prix acceptable, le cycle d’achat, les exigences contractuelles et les conditions de généralisation."
]: body.append(bullet(x))
body += [
    para("Question d’ouverture commerciale", style="Heading2"),
    para("Comment recevez-vous aujourd’hui une alerte du public, comment l’affectez-vous à une équipe et comment démontrez-vous ensuite ce qui a été réalisé ?", bold=True, size=23),
    para("Statut", style="Heading2"),
    para("Cible produit de production définie. Tarifs, matériels Relay, performances radio, niveaux de service et calendrier commercial à qualifier avant engagement client.", italic=True, color="4C5D74")
]

sect = '''<w:sectPr><w:footerReference w:type="default" r:id="rId1"/>
<w:pgSz w:w="12240" w:h="15840"/><w:pgMar w:top="1080" w:right="1224" w:bottom="1080" w:left="1224" w:header="720" w:footer="720" w:gutter="0"/>
<w:cols w:space="720"/><w:docGrid w:linePitch="360"/></w:sectPr>'''
document = f'''<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"
 xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
<w:body>{"".join(body)}{sect}</w:body></w:document>'''

styles = '''<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
<w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii="Aptos" w:hAnsi="Aptos" w:eastAsia="Aptos"/><w:sz w:val="22"/><w:szCs w:val="22"/><w:color w:val="141F33"/></w:rPr></w:rPrDefault>
<w:pPrDefault><w:pPr><w:spacing w:after="140" w:line="276" w:lineRule="auto"/></w:pPr></w:pPrDefault></w:docDefaults>
<w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/></w:style>
<w:style w:type="paragraph" w:styleId="Title"><w:name w:val="Title"/><w:basedOn w:val="Normal"/><w:next w:val="Normal"/>
<w:pPr><w:keepNext/><w:spacing w:before="80" w:after="120"/></w:pPr><w:rPr><w:rFonts w:ascii="Aptos Display" w:hAnsi="Aptos Display"/><w:b/><w:sz w:val="42"/><w:szCs w:val="42"/><w:color w:val="000000"/></w:rPr></w:style>
<w:style w:type="paragraph" w:styleId="Subtitle"><w:name w:val="Subtitle"/><w:basedOn w:val="Normal"/>
<w:pPr><w:spacing w:after="300"/></w:pPr><w:rPr><w:rFonts w:ascii="Aptos" w:hAnsi="Aptos"/><w:sz w:val="24"/><w:szCs w:val="24"/><w:color w:val="4C5D74"/></w:rPr></w:style>
<w:style w:type="paragraph" w:styleId="Heading1"><w:name w:val="heading 1"/><w:basedOn w:val="Normal"/><w:next w:val="Normal"/><w:qFormat/>
<w:pPr><w:keepNext/><w:keepLines/><w:spacing w:before="260" w:after="130"/><w:outlineLvl w:val="0"/></w:pPr>
<w:rPr><w:rFonts w:ascii="Aptos Display" w:hAnsi="Aptos Display"/><w:b/><w:sz w:val="30"/><w:szCs w:val="30"/><w:color w:val="000000"/></w:rPr></w:style>
<w:style w:type="paragraph" w:styleId="Heading2"><w:name w:val="heading 2"/><w:basedOn w:val="Normal"/><w:next w:val="Normal"/><w:qFormat/>
<w:pPr><w:keepNext/><w:keepLines/><w:spacing w:before="180" w:after="80"/><w:outlineLvl w:val="1"/></w:pPr>
<w:rPr><w:rFonts w:ascii="Aptos Display" w:hAnsi="Aptos Display"/><w:b/><w:sz w:val="24"/><w:szCs w:val="24"/><w:color w:val="000000"/></w:rPr></w:style>
</w:styles>'''

numbering = '''<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:numbering xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
<w:abstractNum w:abstractNumId="0"><w:multiLevelType w:val="singleLevel"/>
<w:lvl w:ilvl="0"><w:start w:val="1"/><w:numFmt w:val="bullet"/><w:lvlText w:val="•"/><w:lvlJc w:val="left"/>
<w:pPr><w:tabs><w:tab w:val="num" w:pos="540"/></w:tabs><w:ind w:left="540" w:hanging="270"/></w:pPr>
<w:rPr><w:rFonts w:ascii="Arial" w:hAnsi="Arial"/></w:rPr></w:lvl></w:abstractNum>
<w:num w:numId="1"><w:abstractNumId w:val="0"/></w:num></w:numbering>'''

footer = '''<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:ftr xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
<w:p><w:pPr><w:jc w:val="right"/></w:pPr>
<w:r><w:rPr><w:color w:val="697386"/><w:sz w:val="18"/></w:rPr><w:t>Marsel Pro Event  |  </w:t></w:r>
<w:r><w:fldChar w:fldCharType="begin"/></w:r><w:r><w:instrText xml:space="preserve"> PAGE </w:instrText></w:r>
<w:r><w:fldChar w:fldCharType="end"/></w:r></w:p></w:ftr>'''

types = '''<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>
<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
<Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>
<Override PartName="/word/numbering.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.numbering+xml"/>
<Override PartName="/word/footer1.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.footer+xml"/>
<Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/>
<Override PartName="/docProps/app.xml" ContentType="application/vnd.openxmlformats-officedocument.extended-properties+xml"/>
</Types>'''

rels = '''<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/>
<Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/extended-properties" Target="docProps/app.xml"/>
</Relationships>'''

docrels = '''<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/footer" Target="footer1.xml"/>
<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
<Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/numbering" Target="numbering.xml"/>
</Relationships>'''

now = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
core = f'''<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties"
 xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:dcterms="http://purl.org/dc/terms/"
 xmlns:dcmitype="http://purl.org/dc/dcmitype/" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
<dc:title>Note de synthèse Marsel Pro Event</dc:title><dc:subject>Modèle professionnel à destination du Business Developer</dc:subject>
<dc:creator>Marsel</dc:creator><cp:lastModifiedBy>Marsel</cp:lastModifiedBy>
<dcterms:created xsi:type="dcterms:W3CDTF">{now}</dcterms:created><dcterms:modified xsi:type="dcterms:W3CDTF">{now}</dcterms:modified>
</cp:coreProperties>'''

app = '''<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Properties xmlns="http://schemas.openxmlformats.org/officeDocument/2006/extended-properties"
 xmlns:vt="http://schemas.openxmlformats.org/officeDocument/2006/docPropsVTypes">
<Application>Microsoft Office Word</Application><DocSecurity>0</DocSecurity><ScaleCrop>false</ScaleCrop>
<Company>Marsel</Company><AppVersion>16.0000</AppVersion></Properties>'''

settings = '''<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:settings xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
<w:zoom w:percent="100"/><w:defaultTabStop w:val="720"/><w:updateFields w:val="true"/>
</w:settings>'''

with ZipFile(OUT, "w", ZIP_DEFLATED) as z:
    z.writestr("[Content_Types].xml", types)
    z.writestr("_rels/.rels", rels)
    z.writestr("word/document.xml", document)
    z.writestr("word/styles.xml", styles)
    z.writestr("word/numbering.xml", numbering)
    z.writestr("word/settings.xml", settings)
    z.writestr("word/footer1.xml", footer)
    z.writestr("word/_rels/document.xml.rels", docrels)
    z.writestr("docProps/core.xml", core)
    z.writestr("docProps/app.xml", app)

print(f"Created {OUT} ({OUT.stat().st_size} bytes)")
