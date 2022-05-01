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
import java.util.zip.ZipInputStream
import org.onehippo.cms7.utilities.logging.PrintStreamLogger
import org.slf4j.Logger

class UpdaterTemplate extends BaseNodeUpdateVisitor {

    String ZIP_DOWNLOAD = "https://github.com/ksalic/pacific-home-import/archive/refs/heads/master.zip"
    String EXTRACT_FOLDER = "pacific-home-import-master"
    String INDEX_FILE_NAME = "_index.yaml"
    String[] IMPORT_CATEGORIES = ["post-import"];


    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    PrintStream ps = new PrintStream(this.baos, true, "UTF-8");

    final ConfigurationService configurationService = HippoServiceRegistry.getService(ConfigurationService.class);

    public void initialize(Session session) throws RepositoryException {
        Logger logger = new PrintStreamLogger("import script", 0, ps);
        setLogger(logger);
    }

    boolean doUpdate(Node node) {
        try {
            log.debug("start")
            log.debug(configurationService.getClass().getSimpleName())
            String randomUuid = UUID.randomUUID().toString();
            def imp = FileUtils.getFile(FileUtils.getTempDirectory(), "import" + randomUuid);
            imp.mkdir();
            log.debug(imp.getAbsolutePath());
            def zip = FileUtils.getFile(imp.getAbsolutePath(), "import.zip");
            ValueServer server = new ValueServer();
            server.setValuesFileURL(ZIP_DOWNLOAD)
            FileUtils.copyURLToFile(server.getValuesFileURL(), zip)
            def destDir = FileUtils.getFile(FileUtils.getTempDirectory(), "extract" + randomUuid);
            log.debug(destDir.getAbsolutePath());
            byte[] buffer = new byte[1024];
            ZipInputStream zis = new ZipInputStream(FileUtils.openInputStream(zip));
            ZipEntry zipEntry = zis.getNextEntry();
            while (zipEntry != null) {
                def newFile = FileUtils.getFile(destDir, zipEntry.getName());
                if (zipEntry.isDirectory()) {
                    if (!newFile.isDirectory() && !newFile.mkdirs()) {
                        throw new IOException("Failed to create directory " + newFile);
                    }
                } else {
                    // fix for Windows-created archives
                    def parent = newFile.getParentFile();
                    if (!parent.isDirectory() && !parent.mkdirs()) {
                        throw new IOException("Failed to create directory " + parent);
                    }

                    // write file content
                    def fos = FileUtils.openOutputStream(newFile);
                    int len;
                    while ((len = zis.read(buffer)) > 0) {
                        fos.write(buffer, 0, len);
                    }
                    fos.close();
                }
                zipEntry = zis.getNextEntry();
            }
            zis.closeEntry();
            zis.close();

            def extract = FileUtils.getFile(destDir, EXTRACT_FOLDER);
            def index = FileUtils.getFile(extract, INDEX_FILE_NAME);

            Yaml yaml = new Yaml()
            LinkedHashMap<String, ArrayList> load = yaml.load(FileUtils.openInputStream(index))
            log.debug(load.toString());
            for (String category : IMPORT_CATEGORIES) {
                List preImport = load.get(category);
                for (LinkedHashMap<String, List<Map<String, String>>> importItem : preImport) {
                    String path = importItem.entrySet().getAt(0).getKey();
                    if (node.getSession().itemExists(path)) {
                        Node parentNode = node.getSession().getNode(path);
                        log.debug(parentNode.getPath())
                        List<Map<String, String>> items = importItem.entrySet().getAt(0).getValue();
                        log.debug(path);
                        for (Map<String, String> item : items) {
                            Map.Entry<String, String> itemEntry = item.entrySet().getAt(0)
                            String relativePath = itemEntry.getKey();
                            String fileName = itemEntry.getValue();

                            log.debug(relativePath);
                            if (parentNode.hasNode(relativePath)) {
                                parentNode.getNode(relativePath).remove();
                                log.debug("removing existing node..")
                            }

                            def file = FileUtils.getFile(extract, fileName);
                            if (fileName.endsWith(".zip")) {
                                configurationService.importZippedContent(file, parentNode);
                            } else {
                                configurationService.importPlainYaml(FileUtils.openInputStream(file), parentNode);
                            }

                        }
                    } else {
                        log.warn("path does not exist");
                    }

                }
            }
            imp.deleteDir();
            destDir.deleteDir();
        } catch (Exception e) {
            log.error(e);
        } finally {
            def logNode = node.hasNode("import") ? node.getNode("import") : node.addNode("import", "nt:unstructured");
            String content = new String(baos.toByteArray(), "UTF-8");
            logNode.setProperty("log", content);
        }

    }


    boolean undoUpdate(Node node) {
        throw new UnsupportedOperationException('Updater does not implement undoUpdate method')
    }


}