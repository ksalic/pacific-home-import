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

class UpdaterTemplate extends BaseNodeUpdateVisitor {


    final ConfigurationService configurationService = HippoServiceRegistry.getService(ConfigurationService.class);

    public void initialize(Session session) throws RepositoryException {
    }

    boolean doUpdate(Node node) {
        log.debug("start")
        log.debug(configurationService.getClass().getSimpleName())
        def imp = FileUtils.getFile(FileUtils.getTempDirectory(), "import");
        imp.mkdir();
        log.debug(imp.getAbsolutePath());
        def zip = FileUtils.getFile(imp.getAbsolutePath(), "import.zip");
        ValueServer server = new ValueServer();
        server.setValuesFileURL("https://github.com/ksalic/pacific-home-import/archive/refs/heads/master.zip")
        FileUtils.copyURLToFile(server.getValuesFileURL(), zip)
        def destDir = FileUtils.getFile(FileUtils.getTempDirectory(), "extract");
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

        def extract = FileUtils.getFile(destDir, "pacific-home-import-master");
        def index = FileUtils.getFile(extract, "_index.yaml");

        Yaml yaml = new Yaml()
        LinkedHashMap<String, ArrayList> load = yaml.load(FileUtils.openInputStream(index))
        log.debug(load.toString());
        List preImport = load.get("pre-import");
        for (LinkedHashMap<String, List<Map<String, String>>> importItem : preImport) {
            String path = importItem.entrySet().getAt(0).getKey();
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

        }
    }


    boolean undoUpdate(Node node) {
        throw new UnsupportedOperationException('Updater does not implement undoUpdate method')
    }


}