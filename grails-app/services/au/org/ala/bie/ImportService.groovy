/*
 * Copyright (C) 2016 Atlas of Living Australia
 * All Rights Reserved.
 * The contents of this file are subject to the Mozilla Public
 * License Version 1.1 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of
 * the License at http://www.mozilla.org/MPL/
 * Software distributed under the License is distributed on an "AS
 * IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * rights and limitations under the License.
 */

package au.org.ala.bie

import au.org.ala.bie.search.BIETerms
import au.org.ala.bie.search.IndexDocType
import groovy.json.JsonSlurper
import org.apache.commons.lang.StringUtils
import org.gbif.dwc.terms.DcTerm
import org.gbif.dwc.terms.DwcTerm
import org.gbif.dwc.terms.GbifTerm
import org.gbif.dwc.terms.Term
import org.gbif.dwc.terms.TermFactory
import org.gbif.dwca.io.Archive
import org.gbif.dwca.io.ArchiveFactory
import org.gbif.dwca.io.ArchiveFile
import org.gbif.dwca.record.Record
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.select.Elements

/**
 * Services for data importing.
 */
class ImportService {

    def serviceMethod() {}

    def indexService

    def grailsApplication

    def brokerMessagingTemplate

    def static DYNAMIC_FIELD_EXTENSION = "_s"

    /**
     * Retrieve a set of file paths from the import directory.
     */
    def retrieveAvailableDwCAPaths() {

        def filePaths = []
        def importDir = new File(grailsApplication.config.importDir)
        if (importDir.exists()) {
            File[] expandedDwc = new File(grailsApplication.config.importDir).listFiles()
            expandedDwc.each {
                if (it.isDirectory()) {
                    filePaths << it.getAbsolutePath()
                }
            }
        }
        filePaths
    }

    /**
     * Return a denormalised map lookup. This contains a map from genus level and upwards like so:
     *
     * genusID -> [familyID, orderID,.....]
     * familyID -> [orderID, classID,.....]
     *
     * @return
     */
    private def denormalise(ArchiveFile taxaFile) {

        //read inventory, creating entries in index....
        def childParentMap = [:]
        def parentLess = []
        def parents = [] as Set

        Iterator<Record> iter = taxaFile.iterator()

        while (iter.hasNext()) {

            Record record = iter.next()

            def taxonID = record.id()
            def parentNameUsageID = record.value(DwcTerm.parentNameUsageID)
            def acceptedNameUsageID = record.value(DwcTerm.acceptedNameUsageID)
            def scientificName = record.value(DwcTerm.scientificName)
            def taxonRank = record.value(DwcTerm.taxonRank)?:"".toLowerCase()

            parents << parentNameUsageID

            //if an accepted usage, add to map
            if (acceptedNameUsageID == null || acceptedNameUsageID == "" || taxonID == acceptedNameUsageID) {
                if (parentNameUsageID) {
                    childParentMap.put(taxonID, [cn: scientificName, cr: taxonRank, p: parentNameUsageID])
                } else {
                    parentLess << taxonID
                }
            }
        }

        log("Parent-less: ${parentLess.size()}, Parent-child: ${childParentMap.size()}")

        def taxonDenormLookup = [:]

        log("Starting denormalisation lookups")
        childParentMap.keySet().each {
            //don't bother de-normalising terminal taxa
            if (parents.contains(it)) {
                def list = []
                denormaliseTaxon(it, list, childParentMap)
                taxonDenormLookup.put(it, list)
            }
        }
        log("Finished denormalisation lookups")
        taxonDenormLookup
    }

    /**
     * Recursive function that constructs the lineage.
     *
     * genus, family..., kingdom, null
     *
     * @param id
     * @param currentList
     * @param childParentMap
     * @return
     */
    private List denormaliseTaxon(id, currentList, childParentMap, stackLevel = 0) {
        if (stackLevel > 20) {
            log.warn("Infinite loop detected for ${id} " + currentList)
            return currentList
        }
        def info = childParentMap.get(id)
        if (info && info['p'] && !currentList.contains(id + '|' + info['cn'] + '|' + info['cr'])) {
            currentList << id + '|' + info['cn'] + '|' + info['cr']
            denormaliseTaxon(info['p'], currentList, childParentMap, stackLevel + 1)
        }
        currentList
    }

    /**
     * Import layer information into the index.
     *
     * @return
     */
    def importLayers(){
        def js = new JsonSlurper()
        def url = grailsApplication.config.layersServicesUrl + "/layers"
        log("Requesting layer list from : " +  url)
        def layers = js.parseText(new URL(url).getText("UTF-8"))
        def batch = []
        indexService.deleteFromIndex(IndexDocType.LAYER)
        layers.each { layer ->
            def doc = [:]
            doc["id"] = layer.name
            doc["guid"] = layer.name
            doc["idxtype"] = IndexDocType.LAYER.name()
            doc["name"] = layer.displayname
            doc["description"] = layer.description
            doc["distribution"] = "N/A"
            log("Importing layer: " +  layer.displayname)
            batch << doc
        }
        indexService.indexBatch(batch)
        log("Finished indexing ${layers.size()} layers")
    }

    def importRegions(){
        def js = new JsonSlurper()
        def layers = js.parseText(new URL(grailsApplication.config.layersServicesUrl + "/layers").getText("UTF-8"))
        indexService.deleteFromIndex(IndexDocType.REGION)
        layers.each { layer ->
            if(layer.type == "Contextual") {
                log("Loading regions from layer " + layer.name)
                def batch = []
                def objects = js.parseText(new URL(grailsApplication.config.layersServicesUrl + "/objects/cl" + layer.id).getText("UTF-8"))
                objects.each { object ->

                    def doc = [:]
                    doc["id"] = object.id
                    doc["guid"] = object.pid
                    doc["idxtype"] = IndexDocType.REGION.name()
                    doc["name"] = object.name
                    doc["description"] = layer.displayname
                    doc["distribution"] = "N/A"
                    batch << doc
                }
                if(batch){
                    indexService.indexBatch(batch)
                }
            }
        }
        log("Finished indexing ${layers.size()} region layers")
    }

    def importHabitats(){

        def batch = []
        indexService.deleteFromIndex(IndexDocType.HABITAT)

        //read the DwC metadata
        Archive archive = ArchiveFactory.openArchive(new File("/data/habitat/"));
        ArchiveFile habitatArchiveFile = archive.getCore()

        Iterator<Record> iter = habitatArchiveFile.iterator()

        //get terms
        Term parentHabitatIDTerm = habitatArchiveFile.getField("http://ala.org.au/terms/1.0/parentHabitatID").getTerm()
        Term habitatNameTerm = habitatArchiveFile.getField("http://ala.org.au/terms/1.0/habitatName").getTerm()

        while (iter.hasNext()) {
            Record record = iter.next()
            def habitatID = record.id()
            def parentHabitatID = record.value(parentHabitatIDTerm)
            def habitatName = record.value(habitatNameTerm)
            def doc = [:]
            if(habitatID){
                doc["id"] = habitatID
                doc["guid"] = habitatID
                if(parentHabitatID) {
                    doc["parentGuid"] =  parentHabitatID
                }
                doc["idxtype"] = IndexDocType.HABITAT.name()
                doc["name"] = habitatName
                batch << doc
            }
        }
        indexService.indexBatch(batch)
    }

    /**
     * Import collectory information into the index.
     *
     * @return
     */
    def importCollectory(){
       [
            "dataResource" : IndexDocType.DATARESOURCE,
            "dataProvider" : IndexDocType.DATAPROVIDER,
            "institution" : IndexDocType.INSTITUTION,
            "collection" : IndexDocType.COLLECTION
        ].each { entityType, indexDocType ->
           def js = new JsonSlurper()
           def entities = []
           def drLists = js.parseText(new URL(grailsApplication.config.collectoryUrl + "/${entityType}").getText("UTF-8"))
           log("About to import ${drLists.size()} ${entityType}")
           log("Clearing existing: ${entityType}")
           indexService.deleteFromIndex(indexDocType)

           drLists.each {
               def details = js.parseText(new URL(it.uri).getText("UTF-8"))
               def doc = [:]
               doc["id"] = it.uri
               doc["guid"] = details.alaPublicUrl
               doc["idxtype"] = indexDocType.name()
               doc["name"] = details.name
               doc["description"] = details.description
               doc["distribution"] = "N/A"

               if(details.acronym){
                   doc["acronym"] = details.acronym
               }

               entities << doc

               if(entities.size() > 10){
                   indexService.indexBatch(entities)
                   entities.clear()
               }
           }
           log("Cleared")
           if(entities) {
               indexService.indexBatch(entities)
           }
           log("Finished indexing ${drLists.size()} ${entityType}")
       }
    }

    /**
     * Index WordPress pages
     */
    def importWordPressPages() throws Exception {
        // clear the existing WP index
        indexService.deleteFromIndex(IndexDocType.WORDPRESS)
        // WordPress variables
        String wordPressSitemapUrl = grailsApplication.config.wordPress.sitemapUrl
        String wordPressBaseUrl = grailsApplication.config.wordPress.baseUrl
        List excludedCategories = grailsApplication.config.wordPress.excludedCategories
        String contentOnlyParams = grailsApplication.config.wordPress.contentOnlyParams
        // get List of WordPress document URLs (each page's URL)
        List docUrls = crawlWordPressSite(wordPressSitemapUrl)
        def documentCount = 0
        def totalDocs = docUrls.size()
        def buffer = []
        log("WordPress pages found: ${totalDocs}") // update user via socket

        // slurp and build each SOLR doc (add to buffer)
        docUrls.each { pageUrl ->
            log.debug "indexing url: ${pageUrl}"
            try {
                // Crawl and extract text from WP pages
                Document document = Jsoup.connect(pageUrl + contentOnlyParams).get();
                String title = document.select("head > title").text();
                String id = document.select("head > meta[name=id]").attr("content");
                String shortlink = document.select("head > link[rel=shortlink]").attr("href");
                String bodyText = document.body().text();
                Elements postCategories = document.select("ul[class=post-categories]");
                List categoriesOut = []
                Boolean excludePost = false;

                if (StringUtils.isEmpty(id) && StringUtils.isNotBlank(shortlink)) {
                    // e.g. http://www.ala.org.au/?p=24241
                    id = StringUtils.split(shortlink, "=")[1];
                }

                if (!postCategories.isEmpty()) {
                    // Is a WP post (not page)
                    Elements categoriesIn = postCategories.select("li > a"); // get list of li elements

                    for (Element cat : categoriesIn) {
                        String thisCat = cat.text();

                        if (thisCat != null && excludedCategories.contains(thisCat)) {
                            // exclude category "button" posts
                            excludePost = true;
                        }
                        if (thisCat != null) {
                            // add category to list
                            categoriesOut.add(thisCat.replaceAll(" ", "_"));
                        }
                    }
                }

                if (excludePost) {
                    log("Excluding post (id: ${id} with category: ${categoriesOut.join('|')}")
                    return
                }

                documentCount++;
                // create SOLR doc
                log.debug(documentCount + ". Indexing WP page - id: " + id + " | title: " + title + " | text: " + StringUtils.substring(bodyText, 0, 100) + "... ");
                def doc = [:]
                doc["idxtype"] = IndexDocType.WORDPRESS.name()

                if (StringUtils.isNotBlank(shortlink)) {
                    doc["guid"] = shortlink
                } else if (StringUtils.isNotEmpty(id)) {
                    doc["guid"] = wordPressBaseUrl + id
                    // use page_id based URI instead of permalink in case permalink is too long for id field
                } else {
                    // fallback
                    doc["guid"] = pageUrl
                }

                doc["id"] = "wp" + id // probably not needed but safer to leave in
                doc["name"] = title // , 1.2f
                doc["text"] = bodyText
                doc["linkIdentifier"] = pageUrl
                //doc["australian_s"] = "recorded" // so they appear in default QF search
                doc["categories"] = categoriesOut
                // add to doc to buffer (List)
                buffer << doc
                // update progress bar (number output only)
                if (documentCount > 0) {
                    Double percentDone = (documentCount / totalDocs) * 100
                    log("${percentDone.round(1)}") // progress bar output
                }
            } catch (IOException ex) {
                // catch it so we don't stop indexing other pages
                log("Problem accessing/reading WP page <${pageUrl}>: " + ex.getMessage() + " - document skipped")
                log.warn(ex.getMessage(), ex);
            }
        }
        log("Committing to SOLR...")
        indexService.indexBatch(buffer)
        log("100") // complete progress bar
        log("Import finished.")
    }

    /**
     * Read WP sitemap.xml file and return a list of page URLs
     *
     * @param siteMapUrl
     * @return
     */
    private List crawlWordPressSite(String siteMapUrl) throws Exception {

        List pageUrls = []
        // get list of pages to crawl via Google sitemap xml file
        // Note: sitemap.xml files can be nested, so code may need to read multiple files in the future (recursive function needed)
        Document doc = Jsoup.connect(siteMapUrl).get();
        Elements pages = doc.select("loc");
        log.info("Sitemap file lists " + pages.size() + " pages.");

        for (Element page : pages) {
            // add it to list of page urls Field
            pageUrls.add(page.text());
        }

        pageUrls
    }


    /**
     * Import a DwC-A into this system.
     *
     * @return
     */
    def importDwcA(dwcDir, clearIndex){

        try {
            log("Importing archive from path.." + dwcDir)

            //read the DwC metadata
            Archive archive = ArchiveFactory.openArchive(new File(dwcDir));
            ArchiveFile taxaArchiveFile = archive.getCore()

            //vernacular names extension available?
            ArchiveFile vernacularArchiveFile = archive.getExtension(GbifTerm.VernacularName)
            log("Vernacular extension detected: " + (vernacularArchiveFile != null))

            //dataset extension available?
            ArchiveFile datasetArchiveFile = archive.getExtension(DcTerm.rightsHolder)
            log("Dataset extension detected: " + (datasetArchiveFile != null))

            //dataset extension available?
            ArchiveFile distributionArchiveFile = archive.getExtension(GbifTerm.Distribution)
            log("Distribution extension detected: " + (distributionArchiveFile != null))

            //retrieve taxon rank mappings
            log("Reading taxon ranks..")
            def taxonRanks = readTaxonRankIDs()
            log("Reading taxon ranks.." + taxonRanks.size() + " read.")

            //retrieve images
            def imageMap = indexImages()

            //retrieve common names
            def commonNamesMap = readCommonNames(vernacularArchiveFile)
            log("Common names read: " + commonNamesMap.size())

            //retrieve datasets
            def attributionMap = readAttribution(datasetArchiveFile)
            log("Datasets read: " + attributionMap.size())

            //compile a list of synonyms into memory....
            def synonymMap = readSynonyms(taxaArchiveFile)
            log("Synonyms read: " + synonymMap.size())

            //clear
            if (clearIndex) {
                log("Deleting existing entries in index...")
                indexService.deleteFromIndex(IndexDocType.TAXON)
            } else {
                log("Skipping deleting existing entries in index...")
            }

            //retrieve the denormed taxon lookup
            def denormalised = denormalise(taxaArchiveFile)
            log("De-normalised map..." + denormalised.size())

            //compile a list of synonyms into memory....
            def distributionMap = readDistributions(distributionArchiveFile, denormalised)

            log("Creating entries in index...")

            //read inventory, creating entries in index....
            def alreadyIndexed = [DwcTerm.taxonID,
                                  DwcTerm.datasetID,
                                  DwcTerm.acceptedNameUsageID,
                                  DwcTerm.parentNameUsageID,
                                  DwcTerm.scientificName,
                                  DwcTerm.taxonRank,
                                  DwcTerm.scientificNameAuthorship
            ]

            def buffer = []
            def counter = 0

            Iterator<Record> iter = taxaArchiveFile.iterator()

            while (iter.hasNext()) {

                Record record = iter.next()

                counter++
                def taxonID = record.id()
                def acceptedNameUsageID = record.value(DwcTerm.acceptedNameUsageID)

                if (taxonID == acceptedNameUsageID || acceptedNameUsageID == "" || acceptedNameUsageID == null) {

                    def taxonRank = (record.value(DwcTerm.taxonRank) ?: "").toLowerCase()
                    def scientificName = record.value(DwcTerm.scientificName)
                    def parentNameUsageID = record.value(DwcTerm.parentNameUsageID)
                    def scientificNameAuthorship = record.value(DwcTerm.scientificNameAuthorship)
                    def taxonRankID = taxonRanks.get(taxonRank) ? taxonRanks.get(taxonRank) as Integer : -1

                    //common name
                    def doc = ["idxtype": IndexDocType.TAXON.name()]
                    doc["id"] = UUID.randomUUID().toString()
                    doc["guid"] = taxonID
                    doc["parentGuid"] = parentNameUsageID
                    doc["rank"] = taxonRank
                    doc["rankID"] = taxonRankID
                    doc["scientificName"] = scientificName
                    doc["scientificNameAuthorship"] = scientificNameAuthorship
                    if (scientificNameAuthorship) {
                        doc["nameComplete"] = scientificName + " " + scientificNameAuthorship
                    } else {
                        doc["nameComplete"] = scientificName
                    }

                    def inSchema = [DwcTerm.establishmentMeans, DwcTerm.taxonomicStatus, DwcTerm.taxonConceptID]

                    //index additional fields that are supplied in the core
                    record.terms().each { term ->
                        if (!alreadyIndexed.contains(term)) {
                            if (inSchema.contains(term)) {
                                doc[term.simpleName()] = record.value(term)
                            } else {
                                //use a dynamic field extension
                                doc[term.simpleName() + DYNAMIC_FIELD_EXTENSION] = record.value(term)
                            }
                        }
                    }

                    def attribution = attributionMap.get(record.value(DwcTerm.datasetID))
                    if (attribution) {
                        doc["datasetName"] = attribution["datasetName"]
                        doc["rightsHolder"] = attribution["rightsHolder"]
                    }

                    //retrieve images via scientific name
                    def image = imageMap.get(scientificName)

                    if (image) {
                        doc["image"] = image
                        doc["imageAvailable"] = "yes"
                    } else {
                        doc["imageAvailable"] = "no"
                    }

                    def distributions = distributionMap.get(taxonID)
                    if (distributions) {
                        distributions.each {
                            doc["distribution"] = it
                        }
                    }

                    //common names
                    def commonNames = commonNamesMap.get(taxonID)
                    if (commonNames) {
                        doc["commonName"] = commonNames
                        doc["commonNameExact"] = commonNames
                    }

                    //get de-normalised taxonomy, and add it to the document
                    if (parentNameUsageID) {
                        def taxa = denormalised.get(parentNameUsageID)
                        def processedRanks = []
                        taxa.each { taxon ->

                            //check we have only one value for each rank...
                            def parts = taxon.split('\\|')

                            if (parts.length == 3) {
                                String tID = parts[0]
                                String name = parts[1]
                                String rank = parts[2]
                                String normalisedRank = rank.replaceAll(" ", "_").toLowerCase()
                                if (processedRanks.contains(normalisedRank)) {
                                    log.debug("Duplicated rank: " + normalisedRank + " - " + taxa)
                                } else {
                                    processedRanks << normalisedRank
                                    doc["rk_" + normalisedRank] = name
                                    doc["rkid_" + normalisedRank] = tID
                                }
                            }
                        }
                    }

                    //synonyms - add a separate doc for each
                    def synonyms = synonymMap.get(taxonID)
                    if (synonyms) {
                        synonyms.each { synonym ->

                            //don't add the synonym if it is lexicographically the same
                            if (!synonym['scientificName'].equalsIgnoreCase(scientificName)) {

                                def sdoc = ["idxtype": "TAXON"]
                                sdoc["id"] = UUID.randomUUID().toString()
                                sdoc["guid"] = synonym["taxonID"]
                                sdoc["rank"] = taxonRank
                                sdoc["rankID"] = taxonRankID
                                sdoc["scientificName"] = synonym['scientificName']
                                sdoc["scientificNameAuthorship"] = synonym['scientificNameAuthorship']
                                sdoc["nameComplete"] = synonym['scientificName'] + " " + synonym['scientificNameAuthorship']
                                sdoc["acceptedConceptName"] = scientificName + ' ' + scientificNameAuthorship
                                sdoc["acceptedConceptID"] = taxonID
                                sdoc["taxonomicStatus"] = "synonym"

                                def synAttribution = attributionMap.get(synonym['dataset'])
                                if (synAttribution) {
                                    sdoc["datasetName"] = synAttribution["datasetName"]
                                    sdoc["rightsHolder"] = synAttribution["rightsHolder"]
                                }

                                counter++
                                buffer << sdoc
                            } else {
                                log.debug("Skipping lexicographically the same synonym for " + scientificName)
                            }
                        }
                    }

                    buffer << doc
                }

                if (counter > 0 && counter % 1000 == 0) {
                    if (!buffer.isEmpty()) {
                        log("Adding taxa: ${counter}")
                        indexService.indexBatch(buffer)
                        buffer.clear()
                    }
                }
            }

            //commit remainder
            if (!buffer.isEmpty()) {
                indexService.indexBatch(buffer)
                buffer.clear()
            }
            log("Import finished.")
        } catch (Exception e){
            log("There was problem with the import: " + e.getMessage())
            log("See server logs for more details.")
            log.error(e.getMessage(), e)
        }
    }

    /**
     * Read synonyms into taxonID -> [synonym1, synonym2]
     *
     * @param fileName
     * @return
     */
    private def readDistributions(ArchiveFile distributionsFile, Map denormalisedTaxa) {

        def distributions = [:]

        if(!distributionsFile){
            return distributions
        }

        def iter = distributionsFile.iterator()

        while (iter.hasNext()) {
            def record = iter.next()
            def taxonID = record.id()
            def stateProvince = record.value(DwcTerm.stateProvince)

            def stateProvinces = distributions.get(taxonID)
            if(stateProvinces == null){
                distributions.put(taxonID, [stateProvince])
            } else {
                stateProvinces << stateProvince
            }
        }

        //iterate through these IDs and add the parent IDs to the distributions
        def taxonIDs = distributions.keySet().toArray()
        log("Distributions for child taxa: " + taxonIDs.size())

        taxonIDs.each {
            def taxa = denormalisedTaxa.get(it)
            def processedRanks = []
            taxa.each { taxon ->
                //check we have only one value for each rank...
                def parts = taxon.split('\\|')
                if (parts.length == 3) {
                    String tID = parts[0]
                    def stateProvinces = distributions.get(tID)
                    if(stateProvinces == null){
                        distributions.put(tID, distributions.get(it))
                    } else {
                        def distributionsValues = distributions.get(it)
                        distributionsValues.each {
                            if(!stateProvinces.contains(it)){
                                stateProvinces << it
                            }
                        }
                    }
                }
            }
        }

        log("Distributions for child & parent taxa: " + distributions.keySet().size())
        distributions
    }

    /**
     * Read synonyms into taxonID -> [synonym1, synonym2]
     *
     * @param fileName
     * @return
     */
    private def readSynonyms(ArchiveFile taxaFile) {

        def synonyms = [:]
        def iter = taxaFile.iterator()

        while (iter.hasNext()) {

            def record = iter.next()

            def taxonID = record.id()
            def acceptedNameUsageID = record.value(DwcTerm.acceptedNameUsageID)
            def scientificName = record.value(DwcTerm.scientificName)
            def scientificNameAuthorship = record.value(DwcTerm.scientificNameAuthorship)
            def datasetID = record.value(DwcTerm.datasetID)

            if (taxonID && scientificName && acceptedNameUsageID != taxonID && acceptedNameUsageID != "" && acceptedNameUsageID != null) {
                //we have a synonym
                def synonymList = synonyms.get(acceptedNameUsageID)
                if (!synonymList) {
                    synonymList = []
                    synonyms.put(acceptedNameUsageID, synonymList)
                }

                //lets ignore lexicographically the same names....
                synonymList << [
                        taxonID: taxonID,
                        scientificName : scientificName,
                        scientificNameAuthorship : scientificNameAuthorship,
                        datasetID: datasetID
                ]
            }
        }
        synonyms
    }

    /**
     * Read the attribution file, building a map of ID -> name, dataProvider
     *
     * @param fileName
     * @return
     */
    private def readAttribution(ArchiveFile datasetArchiveFile) {

        def datasets = [:]
        if (!datasetArchiveFile) {
            return datasets
        }

        Iterator<Record> iter = datasetArchiveFile.iterator()
        while (iter.hasNext()) {
            Record record = iter.next()
            def datasetID = record.id()
            def datasetName = record.value(DwcTerm.datasetName)
            def rightsHolder = record.value(DcTerm.rightsHolder)
            datasets.put(datasetID, [datasetName: datasetName, rightsHolder: rightsHolder])
        }
        datasets
    }

    /**
     * Read the common file, building a map of taxonID -> [commonName1, commonName2]
     *
     * @param fileName
     * @return
     */
    private def readCommonNames(ArchiveFile vernacularArchiveFile) {

        def commonNames = [:]

        if(!vernacularArchiveFile){
            return commonNames
        }

        Iterator<Record> iter = vernacularArchiveFile.iterator()
        while (iter.hasNext()) {
            Record record = iter.next()
            def taxonID = record.id()
            def vernacularName = record.value(DwcTerm.vernacularName)
            def nameList = commonNames.get(taxonID)
            if (!nameList) {
                nameList = []
                commonNames.put(taxonID, nameList)
            }
            nameList << vernacularName
        }
        commonNames
    }

    /**
     * Read taxon rank IDs
     *
     * @return
     */
    private def readTaxonRankIDs() {
        Properties props = new Properties()
        InputStream is = this.class.getResourceAsStream("/taxonRanks.properties")
        props.load(is as InputStream)
        def idMap = [:]
        def iter = props.entrySet().iterator()
        while (iter.hasNext()) {
            def entry = iter.next()
            idMap.put(entry.getKey().toLowerCase().trim(), entry.getValue())
        }
        idMap
    }

    private def indexLists(){

        // http://lists.ala.org.au/ws/speciesList?isAuthoritative=eq:true&max=100
        //for each list
            // download http://lists.ala.org.au/speciesListItem/downloadList/{0}
            // read, and add to map
    }

    /**
     * Retrieve map of scientificName -> image details
     *
     * @return
     */
    private def indexImages() {

        if (!grailsApplication.config.indexImages.toBoolean()) {
            return [:]
        }

        def imageMap = [:]
        log("Loading images for the each of the ranks")
        //load images against scientific name
        ["taxon_name", "genus", "family", "order", "class", "phylum"].each {

            log("Loading images for the each of the ${it} ... total thus far ${imageMap.size()}".toString())

            def imagesUrl = grailsApplication.config.biocache.solr.url + "/select?" +
                    "q=*%3A*" +
                    "&fq=multimedia%3AImage" +
                    "&fl=${it}%2C+image_url%2C+data_resource_uid" +
                    "&wt=csv" +
                    "&indent=true" +
                    "&rows=100000"

            //load into map, keyed (for now) on scientific name. The images *should* be keyed on GUID
            new URL(imagesUrl).readLines().each {
                def parts = it.split(",")
                if (parts.length == 3) {
                    //the regular expression removes the subgenus
                    imageMap.put(parts[0].replaceFirst(/\([A-Z]{1}[a-z]{1,}\) /, ""), parts[1])
                }
            }
        }

        log("Images loaded: " + imageMap.size())
        imageMap
    }
    
    def log(msg){
        log.info(msg)
        brokerMessagingTemplate.convertAndSend "/topic/import-feedback", msg.toString()
    }
}