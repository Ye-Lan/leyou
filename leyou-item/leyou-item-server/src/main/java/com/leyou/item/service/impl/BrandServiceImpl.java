package com.leyou.item.service.impl;

import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import com.leyou.common.pojo.PageResult;
import com.leyou.item.mapper.BrandMapper;
import com.leyou.item.pojo.Brand;
import com.leyou.item.service.BrandService;
import com.leyou.parameter.pojo.BrandQueryByPageParameter;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tk.mybatis.mapper.entity.Example;

import java.util.List;

@Service
public class BrandServiceImpl implements BrandService {

    @Autowired
    private BrandMapper brandMapper;

    @Override
    public PageResult<Brand> queryBrandByPage(BrandQueryByPageParameter brandQueryByPageParameter) {

        PageHelper.startPage(brandQueryByPageParameter.getPage(),brandQueryByPageParameter.getRows());

        Example example = new Example(Brand.class);
        if(StringUtils.isNotBlank(brandQueryByPageParameter.getSortBy())){
            example.setOrderByClause(brandQueryByPageParameter.getSortBy() + (brandQueryByPageParameter.getDesc() ? "DESC":"ASC"));
        }

        if(StringUtils.isNotBlank(brandQueryByPageParameter.getKey())){
            example.createCriteria().orLike("name",brandQueryByPageParameter.getKey() +"%").orEqualTo("letter",brandQueryByPageParameter.getKey().toUpperCase());
        }

        List<Brand> list = this.brandMapper.selectByExample(example);

        PageInfo<Brand> pageInfo = new PageInfo<>(list);

        return new PageResult<>(pageInfo.getTotal(),pageInfo.getList());
    }

    @Override
    public void saveBrand(Brand brand, List<Long> cids) {

        this.brandMapper.insertSelective(brand);

        for (Long cid : cids){
            this.brandMapper.insertCategoryBrand(cid,brand.getId());
        }
    }

    @Override
    public void updateBrand(Brand brand, List<Long> cids) {

        deleteByBrandIdInCategoryBrand(brand.getId());

        this.brandMapper.updateByPrimaryKey(brand);

        for (Long cid : cids){
            this.brandMapper.insertCategoryBrand(cid,brand.getId());
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteBrand(Long id) {

        this.brandMapper.deleteByPrimaryKey(id);

        this.brandMapper.deleteByBrandIdInCategoryBrand(id);
    }

    @Override
    public void deleteByBrandIdInCategoryBrand(Long bid) {
        this.brandMapper.deleteByBrandIdInCategoryBrand(bid);
    }

    @Override
    public List<Brand> queryBrandByCategoryId(Long cid) {
        return this.brandMapper.queryBrandByCategoryId(cid);
    }

    @Override
    public List<Brand> queryBrandByBrandIds(List<Long> ids) {
        return this.brandMapper.selectByIdList(ids);
    }
}
