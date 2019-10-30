package com.leyou.item.service.impl;

import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.leyou.common.pojo.PageResult;
import com.leyou.item.bo.SeckillParameter;
import com.leyou.item.bo.SpuBo;
import com.leyou.item.mapper.*;
import com.leyou.item.pojo.*;
import com.leyou.item.service.CategoryService;
import com.leyou.item.service.GoodsService;
import com.leyou.parameter.pojo.SpuQueryByPageParameter;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tk.mybatis.mapper.entity.Example;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class GoodsServiceImpl implements GoodsService {

    @Autowired
    private BrandMapper brandMapper;

    @Autowired
    private CategoryService categoryService;

    @Autowired
    private SpuMapper spuMapper;

    @Autowired
    private SpuDetailMapper spuDetailMapper;

    @Autowired
    private SkuMapper skuMapper;

    @Autowired
    private StockMapper stockMapper;

    @Autowired
    private SeckillMapper seckillMapper;

    private static final String KEY_PREFIX = "leyou:seckill:stock";

    private static final Logger LOGGER = LoggerFactory.getLogger(GoodsServiceImpl.class);

    @Override
    public PageResult<SpuBo> querySpuByPageAndSort(SpuQueryByPageParameter spuQueryByPageParameter) {

        PageHelper.startPage(spuQueryByPageParameter.getPage(),Math.min(spuQueryByPageParameter.getRows(),100));

        Example example = new Example(Spu.class);
        Example.Criteria criteria = example.createCriteria();

        if(spuQueryByPageParameter.getSaleable() != null){
            System.out.print(spuQueryByPageParameter.getSaleable());
            criteria.orEqualTo("saleable",spuQueryByPageParameter.getKey() + "%");
        }

        if(StringUtils.isNotBlank(spuQueryByPageParameter.getKey())){
            criteria.andLike("title","%" + spuQueryByPageParameter.getKey() + "%");
        }

        if(StringUtils.isNotBlank(spuQueryByPageParameter.getSortBy())){
            example.setOrderByClause(spuQueryByPageParameter.getSortBy() + (spuQueryByPageParameter.getDesc() ? "DESC" : "ASC"));
        }

        Page<Spu> pageinfo = (Page<Spu>) this.spuMapper.selectByExample(example);

        List<SpuBo> list = pageinfo.getResult().stream().map(spu -> {

            SpuBo spuBo = new SpuBo();
            BeanUtils.copyProperties(spu,spuBo);

            List<String> nameList = this.categoryService.queryNameByIds(Arrays.asList(spu.getCid1(),spu.getCid2(),spu.getCid3()));

            spuBo.setCname(StringUtils.join(nameList,"/"));

            Brand brand = this.brandMapper.selectByPrimaryKey(spu.getBrandId());
            spuBo.setBname(brand.getName());

            return spuBo;

        }).collect(Collectors.toList());

        return new PageResult<>(pageinfo.getTotal(),list);
    }

    @Override
    public void saveGoods(SpuBo spu) {
        //保存spu
        spu.setSaleable(true);
        spu.setValid(true);
        spu.setCreateTime(new Date());
        spu.setLastUpdateTime(spu.getCreateTime());
        this.spuMapper.insert(spu);

        //保存spu详情
        SpuDetail spuDetail = spu.getSpuDetail();
        spuDetail.setSpuId(spu.getId());
        System.out.println(spuDetail.getSpecifications().length());
        this.spuDetailMapper.insert(spuDetail);

        //保存sku和库存信息
        saveSkuAndStock(spu.getSkus(),spu.getId());
    }

    private void saveSkuAndStock(List<Sku> skus, Long id) {
        for (Sku sku : skus){
            if (!sku.getEnable()){
                continue;
            }
            //保存sku
            sku.setSpuId(id);
            //默认不参加任何促销
            sku.setCreateTime(new Date());
            sku.setLastUpdateTime(sku.getCreateTime());
            this.skuMapper.insert(sku);

            //保存库存信息
            Stock stock = new Stock();
            stock.setSkuId(sku.getId());
            stock.setStock(sku.getStock());
            this.stockMapper.insert(stock);
        }
    }

    @Override
    public SpuBo queryGoodsById(Long id) {

        Spu spu = this.spuMapper.selectByPrimaryKey(id);
        SpuDetail spuDetail = this.spuDetailMapper.selectByPrimaryKey(spu.getId());

        Example example = new Example(Sku.class);
        example.createCriteria().andEqualTo("spuId",spu.getId());
        List<Sku> skuList = this.skuMapper.selectByExample(example);
        List<Long> skuIdList = new ArrayList<>();
        for (Sku sku : skuList){
            skuIdList.add(sku.getId());
        }

        List<Stock> stocks = this.stockMapper.selectByIdList(skuIdList);

        for (Sku sku : skuList){
            for(Stock  stock : stocks){
                if(sku.getId().equals(stock.getSkuId())){
                    sku.setStock(stock.getStock());
                }
            }
        }

        SpuBo spuBo = new SpuBo(spu.getBrandId(),spu.getCid1(),spu.getCid2(),spu.getCid3(),spu.getTitle(),
                spu.getSubTitle(),spu.getSaleable(),spu.getValid(),spu.getCreateTime(),spu.getLastUpdateTime());
        spuBo.setSpuDetail(spuDetail);
        spuBo.setSkus(skuList);

        return spuBo;
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public void updateGoods(SpuBo spuBo) {
        /**
         * 更新策略：
         *      1.判断tb_spu_detail中的spec_template字段新旧是否一致
         *      2.如果一致说明修改的只是库存、价格和是否启用，那么就使用update
         *      3.如果不一致，说明修改了特有属性，那么需要把原来的sku全部删除，然后添加新的sku
         */

        //更新spu
        spuBo.setSaleable(true);
        spuBo.setValid(true);
        spuBo.setLastUpdateTime(new Date());
        this.spuMapper.updateByPrimaryKeySelective(spuBo);

        //更新spu详情
        SpuDetail spuDetail = spuBo.getSpuDetail();
        String oldTemp = this.spuDetailMapper.selectByPrimaryKey(spuBo.getId()).getSpecTemplate();
        if(spuDetail.getSpecifications().equals(oldTemp)){
            //相等，sku update
            //更新sku和库存信息
            updateSkuAndStock(spuBo.getSkus(),spuBo.getId(),true);
        }else{
            //不等，sku insert
            //更新sku和库存信息
            updateSkuAndStock(spuBo.getSkus(),spuBo.getId(),false);
        }

        spuDetail.setSpuId(spuBo.getId());
        this.spuDetailMapper.updateByPrimaryKeySelective(spuDetail);

    }

    private void updateSkuAndStock(List<Sku> skus,Long id,boolean tag) {
        //通过tag判断是insert还是update
        //获取当前数据库中spu_id = id的sku信息
        Example e = new Example(Sku.class);
        e.createCriteria().andEqualTo("spuId",id);
        //oldList中保存数据库中spu_id = id 的全部sku
        List<Sku> oldList = this.skuMapper.selectByExample(e);
        if (tag){
            /**
             * 判断是更新时是否有新的sku被添加：如果对已有数据更新的话，则此时oldList中的数据和skus中的ownSpec是相同的，否则则需要新增
             */
            int count = 0;
            for (Sku sku : skus){
                if (!sku.getEnable()){
                    continue;
                }
                for (Sku old : oldList){
                    if (sku.getOwnSpec().equals(old.getOwnSpec())){
                        System.out.println("更新");
                        //更新
                        List<Sku> list = this.skuMapper.select(old);
                        if (sku.getPrice() == null){
                            sku.setPrice(0L);
                        }
                        if (sku.getStock() == null){
                            sku.setStock(0L);
                        }
                        sku.setId(list.get(0).getId());
                        sku.setCreateTime(list.get(0).getCreateTime());
                        sku.setSpuId(list.get(0).getSpuId());
                        sku.setLastUpdateTime(new Date());
                        this.skuMapper.updateByPrimaryKey(sku);
                        //更新库存信息
                        Stock stock = new Stock();
                        stock.setSkuId(sku.getId());
                        stock.setStock(sku.getStock());
                        this.stockMapper.updateByPrimaryKeySelective(stock);
                        //从oldList中将更新完的数据删除
                        oldList.remove(old);
                        break;
                    }else{
                        //新增
                        count ++ ;
                    }
                }
                if (count == oldList.size() && count != 0){
                    //当只有一个sku时，更新完因为从oldList中将其移除，所以长度变为0，所以要需要加不为0的条件
                    List<Sku> addSku = new ArrayList<>();
                    addSku.add(sku);
                    saveSkuAndStock(addSku,id);
                    count = 0;
                }else {
                    count =0;
                }
            }
            //处理脏数据
            if (oldList.size() != 0){
                for (Sku sku : oldList){
                    this.skuMapper.deleteByPrimaryKey(sku.getId());
                    Example example = new Example(Stock.class);
                    example.createCriteria().andEqualTo("skuId",sku.getId());
                    this.stockMapper.deleteByExample(example);
                }
            }
        }else {
            List<Long> ids = oldList.stream().map(Sku::getId).collect(Collectors.toList());
            //删除以前的库存
            Example example = new Example(Stock.class);
            example.createCriteria().andIn("skuId",ids);
            this.stockMapper.deleteByExample(example);
            //删除以前的sku
            Example example1 = new Example(Sku.class);
            example1.createCriteria().andEqualTo("spuId",id);
            this.skuMapper.deleteByExample(example1);
            //新增sku和库存
            saveSkuAndStock(skus,id);
        }


    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public void goodsSoldOut(Long id) {
        Spu oldSpu = this.spuMapper.selectByPrimaryKey(id);
        Example example = new Example(Sku.class);
        example.createCriteria().andEqualTo("spuId",id);
        List<Sku> skuList = this.skuMapper.selectByExample(example);
        if(oldSpu.getSaleable()){
            oldSpu.setSaleable(false);
            this.spuMapper.updateByPrimaryKeySelective(oldSpu);

            for(Sku sku : skuList){
                sku.setEnable(false);
                this.skuMapper.updateByPrimaryKeySelective(sku);
            }

        }else{

            oldSpu.setSaleable(true);
            this.spuMapper.updateByPrimaryKeySelective(oldSpu);

            for (Sku sku : skuList){
                sku.setEnable(true);
                this.skuMapper.updateByPrimaryKeySelective(sku);
            }

        }
    }

    @Override
    public void deleteGoods(long id) {
        this.spuMapper.deleteByPrimaryKey(id);

        Example example = new Example(SpuDetail.class);
        example.createCriteria().andEqualTo("spuId",id);
        this.spuDetailMapper.deleteByExample(example);

        List<Sku> skuList = this.skuMapper.selectByExample(example);
        for (Sku sku : skuList){
            this.skuMapper.deleteByPrimaryKey(sku.getId());

            this.stockMapper.deleteByPrimaryKey(sku.getId());
        }

    }

    @Override
    public SpuDetail querySpuDetailBySpuId(long id) {
        return this.spuDetailMapper.selectByPrimaryKey(id);
    }

    @Override
    public List<Sku> querySkuBySpuId(Long id) {
        Example example = new Example(Sku.class);
        example.createCriteria().andEqualTo("spuId",id);
        List<Sku> skuList = this.skuMapper.selectByExample(example);

        for (Sku sku : skuList){
            Example temp = new Example(Stock.class);
            temp.createCriteria().andEqualTo("skuId",sku.getId());
            Stock stock = this.stockMapper.selectByExample(temp).get(0);
            sku.setStock(stock.getStock());
        }

        return skuList;
    }

    @Override
    public void sendMessage(Long id, String type) {

    }

    @Override
    public Sku querySkuById(Long id) {
        return this.skuMapper.selectByPrimaryKey(id);
    }

    @Override
    public List<SeckillGoods> querySeckillGoods() {
        Example example = new Example(SeckillGoods.class);
        example.createCriteria().andEqualTo("enable",true);
        List<SeckillGoods> list = this.seckillMapper.selectByExample(example);
        list.forEach(goods -> {
            Stock stock = this.stockMapper.selectByPrimaryKey(goods.getSkuId());
            goods.setStock(stock.getSeckillStock());
            goods.setSeckillTotal(stock.getSeckillTotal());
        });
        return list;
    }

    @Override
    public void addSeckillGoods(SeckillParameter seckillParameter) throws ParseException {
        SimpleDateFormat sdf =  new SimpleDateFormat( "yyyy-MM-dd HH:mm" );
        //1.根据spu_id查询商品
        Long id = seckillParameter.getId();
        Sku sku = this.querySkuById(id);
        //2.插入到秒杀商品表中
        SeckillGoods seckillGoods = new SeckillGoods();
        seckillGoods.setEnable(true);
        seckillGoods.setStartTime(sdf.parse(seckillParameter.getStartTime().trim()));
        seckillGoods.setEndTime(sdf.parse(seckillParameter.getEndTime().trim()));
        seckillGoods.setImage(sku.getImages());
        seckillGoods.setSkuId(sku.getId());
        seckillGoods.setStock(seckillParameter.getCount());
        seckillGoods.setTitle(sku.getTitle());
        seckillGoods.setSeckillPrice(sku.getPrice()*seckillParameter.getDiscount());
        this.seckillMapper.insert(seckillGoods);
        //3.更新对应的库存信息，tb_stock
        Stock stock = stockMapper.selectByPrimaryKey(sku.getId());
        System.out.println(stock);
        if (stock != null) {
            stock.setSeckillStock(stock.getSeckillStock() != null ? stock.getSeckillStock() + seckillParameter.getCount() : seckillParameter.getCount());
            stock.setSeckillTotal(stock.getSeckillTotal() != null ? stock.getSeckillTotal() + seckillParameter.getCount() : seckillParameter.getCount());
            stock.setStock(stock.getStock() - seckillParameter.getCount());
            this.stockMapper.updateByPrimaryKeySelective(stock);
        }else {
            LOGGER.info("更新库存失败！");
        }

        //4.更新redis中的秒杀库存
//        updateSeckillStock();
    }

    /**
     * 更新秒杀商品数量
     * @throws Exception
     */
//    public void updateSeckillStock(){
//        //1.查询可以秒杀的商品
//        List<SeckillGoods> seckillGoods = this.querySeckillGoods();
//        if (seckillGoods == null || seckillGoods.size() == 0){
//            return;
//        }
//        BoundHashOperations<String,Object,Object> hashOperations = this.stringRedisTemplate.boundHashOps(KEY_PREFIX);
//        if (hashOperations.hasKey(KEY_PREFIX)){
//            hashOperations.delete(KEY_PREFIX);
//        }
//        seckillGoods.forEach(goods -> hashOperations.put(goods.getSkuId().toString(),goods.getStock().toString()));
//    }
}
