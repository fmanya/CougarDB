package cougardb.documentdb.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.io.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

@JsonIgnoreProperties(value = { "mapper", "blocks", "indexManager", "index" })
public class CougarCollection {

    private String collectionName;
    private Date creationDate;
    private List<CollectionBlock> blocks = new ArrayList<>();
    private double maxFileSize; // kb
    private int currentId;
    private ObjectMapper mapper = new ObjectMapper();
    private Map<Integer, ReentrantReadWriteLock> blockLocks = new HashMap<Integer, ReentrantReadWriteLock>();
    //private TreeMap<UUID, Integer> index = new TreeMap<>();
    private IndexManager indexManager;

    public CougarCollection(){}

    public ReentrantReadWriteLock getLock(CollectionBlock block){
        if(this.blockLocks.containsKey(block.getId())) {
            return this.blockLocks.get(block.getId());
        } else {
            this.blockLocks.put(block.getId(), new ReentrantReadWriteLock());
            return getLock(block);
        }
    }

    public CougarCollection(String collectionName) {
        this.collectionName = collectionName;
        this.creationDate = new Date();
        this.currentId = 0;
        this.maxFileSize = 1.;
        this.indexManager = new IndexManager(this.collectionName);
        //this.indexManager = new IndexManager(this.collectionName);
        //this.index = new TreeMap<>();
    }

    public void readFileBlocks(boolean readData) {
        this.blocks = new ArrayList<>();
        for (int i = 0; i <= this.currentId; i++) {
            CollectionBlock block = new CollectionBlock(this.collectionName, i, this.maxFileSize);
            if(readData){
                block.readData(getLock(block));
            }
            blocks.add(block);
        }
    }

    public boolean putData(Map<String, Object> data, String id){
        UUID final_id = (id.length() > 0)? UUID.fromString(id) : UUID.randomUUID();
        data.put("id", final_id);
        readFileBlocks(false); // load block list into memory
        try {
            String json = this.mapper.writeValueAsString(data); // data to formatted json string
            final int dataLength = json.getBytes(StandardCharsets.UTF_8).length; // calculate the length of data
            Optional<CollectionBlock> result = blocks.stream().filter(block -> block.getFile().length() + dataLength < maxFileSize*1024).findFirst();
            if(result.isPresent()){ // in case there is a block with enough available space, we insert it there
                CollectionBlock block = result.get();
                ReentrantReadWriteLock lock = getLock(block);
                block.readData(lock);
                block.putData(data, lock);
                this.indexManager.getIndex().put(final_id, block.getId());
                //getIndexManager().addIndex(final_id, block.getId());
            }else{ // otherwise, we create a new block
                this.currentId++;
                CollectionBlock block = new CollectionBlock(this.collectionName, this.currentId, this.maxFileSize);
                block.putData(data, getLock(block));
                blocks.add(block);
                this.blockLocks.put(currentId, new ReentrantReadWriteLock());
                this.indexManager.getIndex().put(final_id, block.getId());//getIndexManager().addIndex(final_id, block.getId());
                return true;
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return false;
    }

    private void writeToFile(File file, String json){
        try {
            BufferedWriter writer = Files.newBufferedWriter(file.toPath());
            writer.write(json);
            writer.flush();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public CollectionBlock getCollectionBlockByID(int id){
        return getBlocks().get(id);
    }

    public TreeMap<UUID, Integer> getIndex(){
        return this.indexManager.getIndex();
    }

    public double getMaxFileSize() {
        return maxFileSize;
    }

    public void setMaxFileSize(double maxFileSize) {
        this.maxFileSize = maxFileSize;
    }

    public String getCollectionName() {
        return collectionName;
    }

    public void setCollectionName(String collectionName) {
        this.collectionName = collectionName;
    }

    public Date getCreationDate() {
        return creationDate;
    }

    public void setCreationDate(Date creationDate) {
        this.creationDate = creationDate;
    }

    public List<CollectionBlock> getBlocks() {
        return blocks;
    }

    public void setBlocks(List<CollectionBlock> blocks) {
        this.blocks = blocks;
    }

    public int getCurrentId() {
        return currentId;
    }

    public void setCurrentId(int currentId) {
        this.currentId = currentId;
    }

    public boolean equals(Object o) {
        if (!(o instanceof CougarCollection)) {
            return false;
        }
        CougarCollection other = (CougarCollection) o;
        return collectionName.equals(other.collectionName);
    }

    public int hashCode() {
        return collectionName.hashCode();
    }

    public void loadMap(){
        this.indexManager = new IndexManager(collectionName);
        for (int i = 0; i <= this.currentId; i++) {
            CollectionBlock block = new CollectionBlock(this.collectionName, i, this.maxFileSize);
            if(block.reloadData()!=null ) {
                for(i=0; i < block.reloadData().size(); i++) {
                    UUID reload = UUID.fromString((String) block.reloadData().get(i).get("id"));
                    indexManager.getIndex().put(reload, block.getId());
                }
            }
        }
    }
}
