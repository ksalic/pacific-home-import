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
        def index = FileUtils.getFile(imp.getAbsolutePath(), "_index.yaml");
        ValueServer server = new ValueServer();
        server.setValuesFileURL("https://raw.githubusercontent.com/ksalic/pacific-home-import/master/_index.yaml")
        FileUtils.copyURLToFile(server.getValuesFileURL(), index)
        def destDir = FileUtils.getFile(FileUtils.getTempDirectory(), "export");
        destDir.mkdir();

        Yaml yaml = new Yaml()
        LinkedHashMap<String, ArrayList> load = yaml.load(FileUtils.openInputStream(index))
        log.debug(load.toString());
        List preImport = load.get("pre-import");
        for (LinkedHashMap<String, List<Map<String, String>>> importItem : preImport) {
            String path = importItem.entrySet().getAt(0).getKey();
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
                }


            }

        }
    }


    boolean undoUpdate(Node node) {
        throw new UnsupportedOperationException('Updater does not implement undoUpdate method')
    }


}