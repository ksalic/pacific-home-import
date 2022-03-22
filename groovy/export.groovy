package org.hippoecm.frontend.plugins.cms.admin.updater

import org.apache.commons.io.FileUtils
import org.apache.commons.math3.random.ValueServer
import org.onehippo.cm.ConfigurationService
import org.onehippo.cms7.services.HippoServiceRegistry
import org.onehippo.repository.update.BaseNodeUpdateVisitor
import org.yaml.snakeyaml.Yaml

import javax.jcr.Node
import javax.jcr.RepositoryException
import javax.jcr.Session
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class UpdaterEditor extends BaseNodeUpdateVisitor {


    final ConfigurationService configurationService = HippoServiceRegistry.getService(ConfigurationService.class);

    public void initialize(Session session) throws RepositoryException {
    }

    boolean doUpdate(Node node) {
        log.debug("start")
        log.debug(configurationService.getClass().getSimpleName())
        def imp = FileUtils.getFile(FileUtils.getTempDirectory(), "import");
        FileUtils.deleteDirectory(imp)
        imp.mkdir();
        log.debug(imp.getAbsolutePath());
        def index = FileUtils.getFile(imp.getAbsolutePath(), "_index.yaml");
        ValueServer server = new ValueServer();
        server.setValuesFileURL("https://raw.githubusercontent.com/ksalic/pacific-home-import/master/_index.yaml")
        FileUtils.copyURLToFile(server.getValuesFileURL(), index)
        def destDir = FileUtils.getFile(FileUtils.getTempDirectory(), "export");
        FileUtils.deleteDirectory(destDir)
        destDir.mkdir();

        Yaml yaml = new Yaml()
        LinkedHashMap<String, ArrayList> load = yaml.load(FileUtils.openInputStream(index))
        log.debug(load.toString());
//        List preImport = load.get("pre-import");
        for (Map.Entry<String, ArrayList> entry : load.entrySet()) {
            def importElement = entry.getValue()
            for (LinkedHashMap<String, List<Map<String, String>>> importItem : importElement) {
                String path = importItem.entrySet().getAt(0).getKey();
                if (node.getSession().itemExists(path)) {
                    Node parentNode = node.getSession().getNode(path);
                    log.debug(parentNode.getPath())
                    List<Map<String, String>> items = importItem.entrySet().getAt(0).getValue();
                    for (Map<String, String> item : items) {
                        Map.Entry<String, String> itemEntry = item.entrySet().getAt(0)
                        String relativePath = itemEntry.getKey();
                        String fileName = itemEntry.getValue();

                        log.debug("checking.." + relativePath);
                        if (parentNode.hasNode(relativePath)) {
                            log.debug("has node..")
                            Node export = parentNode.getNode(relativePath)
                            log.debug("entering existing node..")

                            def file = FileUtils.getFile(destDir, fileName);
                            if (fileName.endsWith(".zip")) {
                                FileUtils.copyFile(configurationService.exportZippedContent(export), file);
                            } else {
                                FileUtils.writeStringToFile(file, configurationService.exportContent(export), "UTF-8");
                            }
                        }else {
                            log.debug("node does not exist")
                        }


                    }
                } else {
                    log.debug("node " + path + " does not exist, unable to export")
                }


            }
        }


        def compressedExport = FileUtils.getFile(destDir, "dirCompressed.zip")
        def fos = FileUtils.openOutputStream(compressedExport);
        ZipOutputStream zipOut = new ZipOutputStream(fos);
        def fileToZip = destDir;

        zipFile(fileToZip, fileToZip.getName(), zipOut);
        zipOut.close();
        fos.close();

        def exportNode = node.addNode("export" + UUID.randomUUID().toString(), "nt:unstructured");
        exportNode.setProperty("zip", FileUtils.openInputStream(compressedExport));


    }

    private static void zipFile(def fileToZip, String fileName, ZipOutputStream zipOut) throws IOException {
        if (fileToZip.isHidden()) {
            return;
        }
        if (fileToZip.isDirectory()) {
            if (fileName.endsWith("/")) {
                zipOut.putNextEntry(new ZipEntry(fileName));
                zipOut.closeEntry();
            } else {
                zipOut.putNextEntry(new ZipEntry(fileName + "/"));
                zipOut.closeEntry();
            }
            def children = fileToZip.listFiles();
            for (def childFile : children) {
                zipFile(childFile, fileName + "/" + childFile.getName(), zipOut);
            }
            return;
        }
        def fis = FileUtils.openInputStream(fileToZip);
        ZipEntry zipEntry = new ZipEntry(fileName);
        zipOut.putNextEntry(zipEntry);
        byte[] bytes = new byte[1024];
        int length;
        while ((length = fis.read(bytes)) >= 0) {
            zipOut.write(bytes, 0, length);
        }
        fis.close();
    }


    boolean undoUpdate(Node node) {
        throw new UnsupportedOperationException('Updater does not implement undoUpdate method')
    }


}