package org.hippoecm.frontend.plugins.cms.admin.updater

import com.fasterxml.jackson.databind.ObjectMapper
import org.apache.commons.io.FileUtils
import org.apache.commons.math3.random.ValueServer
import org.onehippo.cm.ConfigurationService
import org.onehippo.cms7.services.HippoServiceRegistry
import org.onehippo.cms7.utilities.logging.PrintStreamLogger
import org.onehippo.repository.update.BaseNodeUpdateVisitor
import org.slf4j.Logger
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.ResponseEntity
import org.springframework.web.client.RestTemplate
import org.yaml.snakeyaml.Yaml

import javax.jcr.Node
import javax.jcr.RepositoryException
import javax.jcr.Session
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class UpdaterEditor extends BaseNodeUpdateVisitor {

    String INDEX_FILE_NAME = "_index.yaml"
    String GITHUB_AUTH_TOKEN = "ghp_xx"

    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    PrintStream ps = new PrintStream(this.baos, true, "UTF-8");

    final ConfigurationService configurationService = HippoServiceRegistry.getService(ConfigurationService.class);

    public void initialize(Session session) throws RepositoryException {
        Logger logger = new PrintStreamLogger("export script", 0, ps);
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
            def index = FileUtils.getFile(imp.getAbsolutePath(), INDEX_FILE_NAME);
            ValueServer server = new ValueServer();
            server.setValuesFileURL("https://raw.githubusercontent.com/ksalic/pacific-home-import/master/" + INDEX_FILE_NAME)
            FileUtils.copyURLToFile(server.getValuesFileURL(), index)
            def destDir = FileUtils.getFile(FileUtils.getTempDirectory(), "export");
            FileUtils.deleteDirectory(destDir)
            destDir.mkdir();

            Yaml yaml = new Yaml()
            LinkedHashMap<String, ArrayList> load = yaml.load(FileUtils.openInputStream(index))
            log.debug(load.toString());

            ObjectMapper mapper = new ObjectMapper();
            ValueServer restList = new ValueServer();
            restList.setValuesFileURL("https://api.github.com/repos/ksalic/pacific-home-import/contents/")
            List fileRegistry = mapper.readValue(restList.getValuesFileURL(), List.class);
            log.debug("file list size = " + String.valueOf(fileRegistry.size()));

            Map<String, String> fileMap = new HashMap<>();

            for (LinkedHashMap file : fileRegistry) {
                fileMap.put(file.get("name"), file.get("sha"))
            }


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
                                byte[] fileContent = FileUtils.readFileToByteArray(file);
                                String encodedString = Base64.getEncoder().encodeToString(fileContent);

                                Object putFile;
                                if (fileMap.containsKey(fileName)) {
                                    putFile = new UpdateFile();
                                    putFile.setSha(fileMap.get(fileName))
                                    putFile.setContent(encodedString);
                                    putFile.setMessage(randomUuid)
                                } else {
                                    putFile = new UploadFile();
                                    putFile.setContent(encodedString);
                                    putFile.setMessage(randomUuid)
                                }


                                log.debug("posting.. " + fileName + " ..." + putFile.toString())

                                RestTemplate restTemplate = new RestTemplate();
                                String fooResourceUrl = "https://api.github.com/repos/ksalic/pacific-home-import/contents/";

                                HttpHeaders headers = new HttpHeaders();
                                headers.add("Authorization", "Bearer " + GITHUB_AUTH_TOKEN);

                                final HttpEntity entity = new HttpEntity(putFile, headers);

                                log.debug("running exchange..")

                                ResponseEntity<String> response = restTemplate.exchange(fooResourceUrl + fileName, HttpMethod.PUT, entity, String.class);

                                log.debug("result:")
                                log.debug(response.getBody())
                            } else {
                                log.debug("node does not exist")
                            }
                        }
                    } else {
                        log.debug("node " + path + " does not exist, unable to export")
                    }
                }
            }


            imp.deleteDir();
            destDir.deleteDir();
        } catch (Exception e) {
            log.error(e);
        } finally {
            def logNode = node.hasNode("export") ? node.getNode("export") : node.addNode("export", "nt:unstructured");
            String content = new String(baos.toByteArray(), "UTF-8");
            logNode.setProperty("log", content);
        }
    }

    @Override
    void destroy() {
        try {
            ps.close();
            baos.close();
        } catch (Exception e) {
        }

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


    public class UpdateFile {
        private String message;
        private String content;
        private String sha;


        String getMessage() {
            return message
        }

        void setMessage(String message) {
            this.message = message
        }

        String getContent() {
            return content
        }

        void setContent(String content) {
            this.content = content
        }

        String getSha() {
            return sha
        }

        void setSha(String sha) {
            this.sha = sha
        }


        @Override
        public String toString() {
            return "UpdateFile{" +
                    "message='" + message + '\'' +
                    ", content='...'" +
                    ", sha='" + sha + '\'' +
                    '}';
        }
    }

    public class UploadFile {
        private String message;
        private String content;


        String getMessage() {
            return message
        }

        void setMessage(String message) {
            this.message = message
        }

        String getContent() {
            return content
        }

        void setContent(String content) {
            this.content = content
        }


        @Override
        public String toString() {
            return "UploadFile{" +
                    "message='" + message + '\'' +
                    ", content='...'" +
                    '}';
        }
    }


    boolean undoUpdate(Node node) {
        throw new UnsupportedOperationException('Updater does not implement undoUpdate method')
    }


}