package com.leyou.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leyou.bo.SearchRequest;
import com.leyou.client.BrandClient;
import com.leyou.client.CategoryClient;
import com.leyou.client.GoodsClient;
import com.leyou.client.SpecClient;
import com.leyou.item.bo.SpuBo;
import com.leyou.item.pojo.*;
import com.leyou.pojo.Goods;
import com.leyou.repository.GoodsRepository;
import com.leyou.service.SearchService;
import com.leyou.utils.JsonUtils;
import com.leyou.utils.NumberUtils;
import com.leyou.vo.SearchResult;
import org.apache.commons.lang3.StringUtils;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.Operator;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.aggregations.Aggregation;
import org.elasticsearch.search.aggregations.AggregationBuilders;
import org.elasticsearch.search.aggregations.bucket.histogram.InternalHistogram;
import org.elasticsearch.search.aggregations.bucket.terms.LongTerms;
import org.elasticsearch.search.aggregations.bucket.terms.StringTerms;
import org.elasticsearch.search.aggregations.metrics.stats.InternalStats;
import org.elasticsearch.search.sort.SortBuilders;
import org.elasticsearch.search.sort.SortOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.elasticsearch.core.ElasticsearchTemplate;
import org.springframework.data.elasticsearch.core.aggregation.AggregatedPage;
import org.springframework.data.elasticsearch.core.query.FetchSourceFilter;
import org.springframework.data.elasticsearch.core.query.NativeSearchQueryBuilder;


import java.io.IOException;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

public class SearchServiceImpl implements SearchService {
    @Autowired
    private CategoryClient categoryClient;

    @Autowired
    private GoodsClient goodsClient;

    @Autowired
    private GoodsRepository goodsRepository;

    @Autowired
    private BrandClient brandClient;

    @Autowired
    private SpecClient specClient;

    @Autowired
    private ElasticsearchTemplate elasticsearchTemplate;

    private ObjectMapper mapper = new ObjectMapper();

    @Override
    public Goods buildGoods(Spu spu) throws IOException {

        Goods goods = new Goods();

        //1.查询商品分类名称
        List<String> names = this.categoryClient.queryNameByIds(Arrays.asList(spu.getCid1(),spu.getCid2(),spu.getCid3())).getBody();
        //2.查询sku
        List<Sku> skus = this.goodsClient.querySkuBySpuId(spu.getId());
        //3.查询详情
        SpuDetail spuDetail = this.goodsClient.querySpuDetailBySpuId(spu.getId());

        //4.处理sku,仅封装id,价格、标题、图片、并获得价格集合
        List<Long> prices = new ArrayList<>();
        List<Map<String,Object>> skuLists = new ArrayList<>();
        skus.forEach(sku -> {
            prices.add(sku.getPrice());
            Map<String,Object> skuMap = new HashMap<>();
            skuMap.put("id",sku.getId());
            skuMap.put("title",sku.getTitle());
            skuMap.put("price",sku.getPrice());

            //取第一张图片
            skuMap.put("image", StringUtils.isBlank(sku.getImages()) ? "" : StringUtils.split(sku.getImages(),",")[0]);
            skuLists.add(skuMap);
        });

        //提取公共属性
        List<Map<String,Object>> genericSpecs = this.mapper.readValue(spuDetail.getSpecifications(),new TypeReference<List<Map<String,Object>>>(){});

        //提取特有属性
        Map<String,Object> specialSpecs = mapper.readValue(spuDetail.getSpecTemplate(),new TypeReference<Map<String,Object>>(){});

        //过滤规格模板，把所有可搜索的信息保存到Map中
        Map<String,Object> specMap = new HashMap<>();

        String searchable = "searchable";
        String v = "v";
        String k = "k";
        String options = "options";

        genericSpecs.forEach(m -> {
            List<Map<String,Object>> params = (List<Map<String,Object>>) m.get("params");
            params.forEach(spe ->{
                if ((Boolean) spe.get(searchable)){
                    if(spe.get(v) != null){
                        specMap.put(spe.get(k).toString(),spe.get(v));
                    }else if(spe.get(options) != null){
                        specMap.put(spe.get(k).toString(),spe.get(options));
                    }
                }
            });
        });

        goods.setId(spu.getId());
        goods.setSubTitle(spu.getSubTitle());
        goods.setBrandId(spu.getBrandId());
        goods.setCid1(spu.getCid1());
        goods.setCid2(spu.getCid2());
        goods.setCid3(spu.getCid3());
        goods.setCreateTime(spu.getCreateTime());
        goods.setAll(spu.getTitle() + " " + StringUtils.join(names, " "));
        goods.setPrice(prices);
        goods.setSkus(mapper.writeValueAsString(skuLists));
        goods.setSpecs(specMap);
        return goods;
    }

    @Override
    public SearchResult<Goods> search(SearchRequest searchRequest) {
        String key = searchRequest.getKey();

        if(StringUtils.isBlank(key)){
            return null;
        }

        // 创建查询构造器
        NativeSearchQueryBuilder queryBuilder = new NativeSearchQueryBuilder();
        //1 构建查询条件
        //1.1 对关键字进行全文检索查询
        QueryBuilder basicQuery = this.buildBasicQueryWithFilter(searchRequest);
        queryBuilder.withQuery(basicQuery);
        queryBuilder.withSourceFilter(new FetchSourceFilter(new String[]{"id","skus","subTitle"},null));

        searchWithPageAndSort(queryBuilder,searchRequest);

        String categoryAggName = "category";

        String brandAggName = "brand";
        //对商品分类进行聚合
        queryBuilder.addAggregation(AggregationBuilders.terms(categoryAggName).field("cid3"));
        //对品牌进行聚合
        queryBuilder.addAggregation(AggregationBuilders.terms(brandAggName).field("brandId"));

        AggregatedPage<Goods> pageInfo = (AggregatedPage<Goods>) this.goodsRepository.search(queryBuilder.build());

        Long total = pageInfo.getTotalElements();
        int totalPage = pageInfo.getTotalPages();

        //3.2 商品分类的聚合结果
        List<Category> categories = getCategoryAggResult(pageInfo.getAggregation(categoryAggName));

        //3.3 品牌的聚合结果
        List<Brand> brands = getBrandAggResult(pageInfo.getAggregation(brandAggName));

        List<Map<String,Object>> specs = null;
        if (categories.size() == 1){
            //如果商品分类只有一个进行聚合，并根据分类与基本查询条件聚合
            specs = getSpec(categories.get(0).getId(),basicQuery);
        }

        return new SearchResult<>(total, (long)totalPage,pageInfo.getContent(),categories,brands,specs);
    }

    private List<Map<String, Object>> getSpec(Long id, QueryBuilder basicQuery) {
        String specsJSONstr = this.specClient.querySpecificationByCategoryId(id).getBody();

        List<Map<String,Object>> specs = null;

        specs = JsonUtils.nativeRead(specsJSONstr, new TypeReference<List<Map<String, Object>>>() {
        });

        Set<String> strSpec = new HashSet<>();
        //准备map，用来保存数值规格参数名及单位
        Map<String,String> numericalUnits = new HashMap<>();
        String searchable = "searchable";
        String numerical = "numerical";
        String k = "k";
        String unit = "unit";

        for(Map<String,Object> spec : specs){
            List<Map<String, Object>> params = (List<Map<String, Object>>) spec.get("params");
            params.forEach(param -> {
                if((Boolean) param.get(searchable)){
                    if(param.containsKey(numerical) && (boolean) param.get(numerical)){
                        numericalUnits.put(param.get(k).toString(),param.get(unit).toString());
                    }else{
                        strSpec.add(param.get(k).toString());
                    }
                }
            });
        }

        Map<String,Double> numericalInterval = getNumberInterval(id,numericalUnits.keySet());
        return aggForSpec(strSpec,numericalInterval,numericalUnits,basicQuery);
    }

    private List<Map<String, Object>> aggForSpec(Set<String> strSpec, Map<String, Double> numericalInterval, Map<String, String> numericalUnits, QueryBuilder basicQuery) {
        List<Map<String,Object>> specs = new ArrayList<>();
        //准备查询条件
        NativeSearchQueryBuilder queryBuilder = new NativeSearchQueryBuilder();
        queryBuilder.withQuery(basicQuery);
        //聚合数值类型
        for (Map.Entry<String,Double> entry : numericalInterval.entrySet()) {
            queryBuilder.addAggregation(AggregationBuilders.histogram(entry.getKey()).field("specs." + entry.getKey()).interval(entry.getValue()).minDocCount(1));
        }
        //聚合字符串
        for (String key :strSpec){
            queryBuilder.addAggregation(AggregationBuilders.terms(key).field("specs."+key+".keyword"));
        }
        //解析聚合结果
        Map<String,Aggregation> aggregationMap = this.elasticsearchTemplate.query(queryBuilder.build(), SearchResponse:: getAggregations).asMap();

        //解析数值类型
        for (Map.Entry<String,Double> entry :numericalInterval.entrySet()){
            Map<String,Object> spec = new HashMap<>();
            String key = entry.getKey();
            spec.put("k",key);
            spec.put("unit",numericalUnits.get(key));
            //获取聚合结果
            InternalHistogram histogram = (InternalHistogram) aggregationMap.get(key);
            spec.put("options",histogram.getBuckets().stream().map(bucket -> {
                Double begin = (Double) bucket.getKey();
                Double end = begin + numericalInterval.get(key);
                //对begin和end取整
                if (NumberUtils.isInt(begin) && NumberUtils.isInt(end)){
                    //确实是整数，直接取整
                    return begin.intValue() + "-" + end.intValue();
                }else {
                    //小数，取2位小数
                    begin = NumberUtils.scale(begin,2);
                    end = NumberUtils.scale(end,2);
                    return begin + "-" + end;
                }
            }).collect(Collectors.toList()));
            specs.add(spec);
        }

        //解析字符串类型
        strSpec.forEach(key -> {
            Map<String,Object> spec = new HashMap<>();
            spec.put("k",key);
            StringTerms terms = (StringTerms) aggregationMap.get(key);
            spec.put("options",terms.getBuckets().stream().map((Function<StringTerms.Bucket, Object>) StringTerms.Bucket::getKeyAsString).collect(Collectors.toList()));
            specs.add(spec);
        });
        return specs;
    }

    private Map<String, Double> getNumberInterval(Long id, Set<String> keySet) {
        Map<String,Double> numbericalSpecs = new HashMap<>();
        //准备查询条件
        NativeSearchQueryBuilder queryBuilder = new NativeSearchQueryBuilder();
        //不查询任何数据
        queryBuilder.withQuery(QueryBuilders.termQuery("cid3",id.toString())).withSourceFilter(new FetchSourceFilter(new String[]{""},null)).withPageable(PageRequest.of(0,1));
        //添加stats类型的聚合,同时返回avg、max、min、sum、count等
        for (String key : keySet){
            queryBuilder.addAggregation(AggregationBuilders.stats(key).field("specs." + key));
        }
        Map<String,Aggregation> aggregationMap = this.elasticsearchTemplate.query(queryBuilder.build(),
                searchResponse -> searchResponse.getAggregations().asMap()
        );
        for (String key : keySet){
            InternalStats stats = (InternalStats) aggregationMap.get(key);
            double interval = getInterval(stats.getMin(),stats.getMax(),stats.getSum());
            numbericalSpecs.put(key,interval);
        }
        return numbericalSpecs;
    }

    /**
     * 根据最小值，最大值，sum计算interval
     * @param min
     * @param max
     * @param sum
     * @return
     */
    private double getInterval(double min, double max, Double sum) {
        //显示7个区间
        double interval = (max - min) / 6.0d;
        //判断是否是小数
        if (sum.intValue() == sum){
            //不是小数，要取整十、整百
            int length = StringUtils.substringBefore(String.valueOf(interval),".").length();
            double factor = Math.pow(10.0,length - 1);
            return Math.round(interval / factor)*factor;
        }else {
            //是小数的话就保留一位小数
            return NumberUtils.scale(interval,1);
        }
    }

    private List<Brand> getBrandAggResult(Aggregation aggregation) {
        LongTerms brandAgg = (LongTerms) aggregation;
        List<Long> bids = new ArrayList<>();
        for (LongTerms.Bucket bucket : brandAgg.getBuckets()){
            bids.add(bucket.getKeyAsNumber().longValue());
        }
        //根据品牌id查询品牌
        return this.brandClient.queryBrandByIds(bids);
    }

    private QueryBuilder buildBasicQueryWithFilter(SearchRequest searchRequest){
        BoolQueryBuilder queryBuilder = QueryBuilders.boolQuery();
        //基本查询条件
        queryBuilder.must(QueryBuilders.matchQuery("all",searchRequest.getKey()).operator(Operator.AND));
        //过滤条件构造器
        BoolQueryBuilder filterQueryBuilder = QueryBuilders.boolQuery();
        //整理过滤条件
        Map<String,String> filter = searchRequest.getFilter();
        for(Map.Entry<String,String> entry : filter.entrySet()){
            String key = entry.getKey();
            String value = entry.getValue();
            String regex = "^(\\d+\\.?\\d*)-(\\d+\\.?\\d*)$";
            if(!"key".equals(key)){
                if("price".equals(key)){
                    if(!value.contains("元以上")){
                        String[] nums = StringUtils.substringBefore(value,"元").split("-");
                        filterQueryBuilder.must(QueryBuilders.rangeQuery(key).gte(Double.valueOf(nums[0]) * 100).lt(Double.valueOf(nums[1]) * 100));
                    }else{
                        String num = StringUtils.substringBefore(value,"元以上");
                        filterQueryBuilder.must(QueryBuilders.rangeQuery(key).gte(Double.valueOf(num) * 100));
                    }
                }else{
                    if(value.matches(regex)){
                        Double[] nums = NumberUtils.searchNumber(value,regex);
                        //数值类型进行范围查询   lt:小于  gte:大于等于
                        filterQueryBuilder.must(QueryBuilders.rangeQuery("specs." + key).gte(nums[0]).lt(nums[1]));
                    }else{
                        //商品分类和品牌要特殊处理
                        if(key != "cid3" && key != "brandId"){
                          key = "specs." + key + ".keyword";
                        }
                        //字符串类型，进行term查询
                        filterQueryBuilder.must(QueryBuilders.termQuery(key,value));
                    }
                }
            }else{
                break;
            }
        }

        queryBuilder.filter(filterQueryBuilder);
        return queryBuilder;
    }

    private void searchWithPageAndSort(NativeSearchQueryBuilder queryBuilder,SearchRequest request){

        int page = request.getPage();
        int size = request.getDefaultSize();

        queryBuilder.withPageable(PageRequest.of(page - 1,size));

        String sortBy = request.getSortBy();
        Boolean desc = request.getDescending();
        if(StringUtils.isNotBlank(sortBy)){
            queryBuilder.withSort(SortBuilders.fieldSort(sortBy).order(desc ? SortOrder.DESC : SortOrder.ASC));
        }
    }

    private List<Category> getCategoryAggResult(Aggregation aggregation) {
        LongTerms brandAgg = (LongTerms) aggregation;
        List<Long> cids = new ArrayList<>();
        for (LongTerms.Bucket bucket : brandAgg.getBuckets()){
            cids.add(bucket.getKeyAsNumber().longValue());
        }
        //根据id查询分类名称
        return this.categoryClient.queryCategoryByIds(cids).getBody();
    }

    @Override
    public void createIndex(Long id) throws IOException {
        SpuBo spuBo = this.goodsClient.queryGoodsById(id);

        Goods goods = this.buildGoods(spuBo);

        this.goodsRepository.save(goods);
    }

    @Override
    public void deleteIndex(Long id) {
        this.goodsRepository.deleteById(id);
    }
}
