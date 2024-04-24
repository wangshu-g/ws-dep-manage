package com.ws.base.service;

import cn.hutool.core.util.StrUtil;
import com.alibaba.excel.EasyExcel;
import com.alibaba.fastjson.JSON;
import com.ws.annotation.Column;
import com.ws.annotation.Data;
import com.ws.base.controller.daoru.ModelDataListener;
import com.ws.base.mapper.BaseDataMapper;
import com.ws.base.model.BaseModel;
import com.ws.enu.CommonErrorInfo;
import com.ws.exception.IException;
import com.ws.tool.CacheTool;
import com.ws.tool.ExcelUtil;
import com.ws.tool.StringUtil;
import jakarta.servlet.http.HttpServletResponse;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.mybatis.spring.MyBatisSystemException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

/**
 * @author GSF
 * <p>AbstractBaseDataService implements BaseService</p>
 */
public abstract class AbstractBaseDataService<M extends BaseDataMapper<T>, T extends BaseModel> implements BaseDataService<M, T> {

    public Logger log = LoggerFactory.getLogger(this.getClass());

    /**
     * <p>获取对应mapper</p>
     *
     * @return M extends BaseMapper<T extends BaseModel>
     **/
    public abstract M getMapper();

    /**
     * <p>保存</p>
     * <p>如果转更新,防止更新参数和条件参数冲突,强制将所有参数添加new{列名}作为新值,并且只会保留id作为唯一更新条件</p>
     * <p>当然更新还是建议老老实实使用update去更,一是即使不存在大部分情况不会造成很大损失,二是使用这个会多一次查询验证</p>
     * <p>再提一下,前端调用save更新时,不需要使用new{列名}作为参数,因为save最终调用的是save(T model),你使用new{列名}作为参数在转换为实体类的时候参数会被过滤掉.总之还是老老实实调用update</p>
     *
     * @param model extends {@link BaseModel}
     * @return BaseModel
     **/
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int save(@NotNull T model) {
        Field modelPrimaryField = this.getModelPrimaryField();
        if (Objects.isNull(modelPrimaryField)) {
            log.error("实体类需要指定主键字段");
            throw new IException(CommonErrorInfo.SERVER_ERROR);
        }
        Object primaryValue = model.modelAnyValueByFieldName(modelPrimaryField.getName());
        if (StringUtil.isNotEmpty(primaryValue) && Objects.nonNull(this.select(modelPrimaryField.getName(), primaryValue))) {
            return this.update(model);
        }
        model = this.saveParamFilter(model);
        if (this.saveValidate(model)) {
            return this.getMapper()._save(model);
        }
        throw new IException(CommonErrorInfo.BODY_NOT_MATCH);
    }

    /**
     * <p>保存</p>
     *
     * @param map {columnName : value}
     * @return int
     **/
    @Transactional(rollbackFor = Exception.class)
    public int save(@NotNull Map<String, Object> map) {
        T model;
        try {
            model = this.getModelClazz().getConstructor().newInstance();
        } catch (NoSuchMethodException | InvocationTargetException | InstantiationException | IllegalAccessException e) {
            log.error("获取实体类实例失败,请检查泛型", e);
            throw new IException(CommonErrorInfo.SERVER_ERROR);
        }
        model.setModelValuesFromMapByFieldName(map);
        return this.save(model);
    }

    public T saveParamFilter(@NotNull T model) {
        Field modelPrimaryField = this.getModelPrimaryField();
        if (Objects.isNull(modelPrimaryField)) {
            log.error("实体类需要指定主键字段");
            throw new IException(CommonErrorInfo.SERVER_ERROR);
        }
        if (StringUtil.isEmpty(model.modelAnyValueByFieldName(modelPrimaryField.getName())) && modelPrimaryField.getType().equals(String.class)) {
            model.setModelAnyValueByFieldName(modelPrimaryField.getName(), this.getUUID());
        }
        if (model.fieldIsExist("createdAt") && Objects.isNull(model.modelAnyValueByFieldName("createdAt"))) {
            model.setModelAnyValueByFieldName("createdAt", new Date());
        }
        return model;
    }

    /**
     * <p>保存验证</p>
     *
     * @param model 实体类
     * @return boolean
     **/
    public boolean saveValidate(@NotNull T model) {
        log.info("保存操作参数: {}", model.toJson());
        return true;
    }

    /**
     * <p>批量更新</p>
     * <p>不会去校验是否存在</p>
     *
     * @param modelList 实体类列表
     * @return int
     **/
    @Override
    @Transactional
    public int batchSave(@NotNull List<T> modelList) {
        List<T> newModelList = modelList.stream().map(model -> {
            model = this.saveParamFilter(model);
            return model;
        }).toList();
        if (newModelList.stream().allMatch(this::saveValidate)) {
            return this.getMapper()._batchSave(modelList);
        }
        throw new IException(CommonErrorInfo.BODY_NOT_MATCH);
    }

    /**
     * <p>删除</p>
     *
     * @param map {columnName : value}
     * @return BaseModel
     **/
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int delete(@NotNull Map<String, Object> map) {
        map = this.deleteParamFilter(map);
        if (this.deleteValidate(map)) {
            return this.getMapper()._delete(map);
        }
        throw new IException(CommonErrorInfo.BODY_NOT_MATCH);
    }

    @Transactional(rollbackFor = Exception.class)
    public int delete(@NotNull Object... keyValuesArray) {
        Map<String, Object> map = new HashMap<>();
        int length = keyValuesArray.length;
        for (int i = 0; i < keyValuesArray.length; i++) {
            if (i % 2 == 0 && length >= i + 1) {
                map.put(String.valueOf(keyValuesArray[i]), keyValuesArray[i + 1]);
            }
        }
        return this.delete(map);
    }

    /**
     * <p>删除</p>
     *
     * @param model extends BaseModel
     * @return int
     **/
    @Transactional(rollbackFor = Exception.class)
    public int delete(@NotNull T model) {
        return this.delete(model.toMap());
    }

    /**
     * <p>删除</p>
     *
     * @param id id
     * @return int
     **/
    @Transactional(rollbackFor = Exception.class)
    public int delete(String id) {
        Map<String, Object> map = new HashMap<>(1);
        Field modelPrimaryField = this.getModelPrimaryField();
        if (Objects.isNull(modelPrimaryField)) {
            log.error("实体类需要指定主键字段");
            throw new IException(CommonErrorInfo.SERVER_ERROR);
        }
        map.put(modelPrimaryField.getName(), id);
        return this.delete(map);
    }

    public Map<String, Object> deleteParamFilter(@NotNull Map<String, Object> map) {
        if (map.isEmpty()) {
            log.error("删除操作参数不能为空!如场景需要,建议单独写一个方法(也可重写该验证方法,但不建议!)");
            throw new IException(CommonErrorInfo.BODY_NOT_MATCH);
        }
        return map;
    }

    /**
     * <p>删除验证</p>
     *
     * @param map {columnName : value}
     * @return boolean
     **/
    public boolean deleteValidate(@NotNull Map<String, Object> map) {
        log.info("删除操作参数: {}", JSON.toJSONString(map));
        return true;
    }

    /**
     * <p>更新</p>
     *
     * @param map {columnName : value}
     * @return int
     **/
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int update(@NotNull Map<String, Object> map) {
        map = updateParamFilter(map);
        if (this.updateValidate(map)) {
            return this.getMapper()._update(map);
        }
        throw new IException(CommonErrorInfo.BODY_NOT_MATCH);
    }

    @Transactional(rollbackFor = Exception.class)
    public int update(String id, @NotNull String column1, Object param1) {
        Map<String, Object> map = new HashMap<>(1);
        map.put("id", id);
        if (!column1.startsWith("new")) {
            map.put(StringUtil.concat("new", StrUtil.upperFirst(column1)), param1);
        }
        return this.update(map);
    }

    /**
     * <p>更新</p>
     * <p>防止更新参数和条件参数冲突,会强制将实体类所有字段添加new{列名}作为新值,并且只会保留id作为唯一更新条件</p>
     *
     * @param model extends BaseModel
     * @return int
     **/
    @Transactional(rollbackFor = Exception.class)
    public int update(@NotNull T model) {
        Map<String, Object> param = new HashMap<>();
        Map<String, Object> temp = model.toMap();
        temp.forEach((k, v) -> param.put(StringUtil.concat("new", StrUtil.upperFirst(k)), v));
        Object id = model.modelAnyValueByFieldName("id");
        if (StringUtil.isEmpty(id)) {
            param.putAll(temp);
        } else {
            param.put("id", id);
        }
        log.warn("实体类更新.为了防止更新参数和条件参数冲突,参数强制修改为: {}", param);
        return this.update(param);
    }

    @Transactional(rollbackFor = Exception.class)
    public int update(@NotNull Object... keyValuesArray) {
        Map<String, Object> map = new HashMap<>();
        int length = keyValuesArray.length;
        for (int i = 0; i < keyValuesArray.length; i++) {
            if (i % 2 == 0 && length >= i + 1) {
                map.put(String.valueOf(keyValuesArray[i]), keyValuesArray[i + 1]);
            }
        }
        return this.update(map);
    }

    /**
     * <p>更新参数过滤,这里的new是为了防止更新条件和更新值参数冲突,只有更新操作强制这样做(有更好方案可重写替换)</p>
     *
     * @param map {columnName : value}
     * @return Map<String, Object>
     **/
    public Map<String, Object> updateParamFilter(@NotNull Map<String, Object> map) {
        if (map.isEmpty()) {
            log.error("更新操作参数不能为空!如场景需要,建议单独写一个方法(也可重写该验证方法,但不建议!)");
            throw new IException(CommonErrorInfo.BODY_NOT_MATCH);
        }
        if (StringUtil.isEmpty(map.get("newUpdatedAt"))) {
            map.put("newUpdatedAt", new Date());
        }
        map.remove("newCreatedAt");
        return map;
    }

    /**
     * <p>更新验证</p>
     *
     * @param map {columnName : value}
     * @return boolean
     **/
    public boolean updateValidate(@NotNull Map<String, Object> map) {
        log.info("更新操作参数: {}", map);
        if (map.keySet().stream().filter(key -> !key.startsWith("new")).toList().isEmpty()) {
            log.warn("更新条件参数没有有效新值,注意检查相关代码");
        }
        return true;
    }

    /**
     * <p>查询1条</p>
     *
     * @param map {columnName : value}
     * @return T extends BaseModel
     **/
    @Override
    public @Nullable T select(@NotNull Map<String, Object> map) {
        map = this.selectParamFilter(map);
        if (this.selectValidate(map)) {
            try {
                return this.getMapper()._select(map);
            } catch (MyBatisSystemException e) {
                log.error("异常: ", e);
                throw new IException(CommonErrorInfo.SERVER_ERROR);
            }
        }
        throw new IException(CommonErrorInfo.BODY_NOT_MATCH);
    }

    public Map<String, Object> selectParamFilter(@NotNull Map<String, Object> map) {
        if (map.isEmpty()) {
            throw new IException(CommonErrorInfo.BODY_NOT_MATCH);
        }
        return map;
    }

    public boolean selectValidate(@NotNull Map<String, Object> map) {
        log.info("查询操作参数: {}", JSON.toJSONString(map));
        return true;
    }

    /**
     * <p>查询1条</p>
     *
     * @param model extends BaseModel
     * @return T extends BaseModel
     **/
    public @Nullable T select(@NotNull T model) {
        return this.select(model.toMap());
    }

    /**
     * <p>查询1条</p>
     *
     * @param id id
     * @return T extends BaseModel
     **/
    public @Nullable T select(String id) {
        if (StringUtil.isEmpty(id)) {
            return null;
        }
        Map<String, Object> map = new HashMap<>(1);
        map.put("id", id);
        return this.select(map);
    }

    /**
     * <p>查询1条</p>
     *
     * @param column1 columnName
     * @param param1  value
     * @param column2 columnName
     * @param param2  value
     * @return T extends BaseModel
     **/
    public @Nullable T select(String column1, Object param1, String column2, Object param2) {
        Map<String, Object> map = new HashMap<>(1);
        map.put(column1, param1);
        map.put(column2, param2);
        return this.select(map);
    }

    public @Nullable T select(@NotNull Object... keyValuesArray) {
        Map<String, Object> map = new HashMap<>();
        int length = keyValuesArray.length;
        for (int i = 0; i < length; i++) {
            if (i % 2 == 0 && length > i + 1) {
                map.put(String.valueOf(keyValuesArray[i]), keyValuesArray[i + 1]);
            }
        }
        return this.select(map);
    }

    /**
     * <p>查询列表</p>
     *
     * @param map {columnName : value}
     * @return List<T> T extends BaseModel
     **/
    @Override
    public List<Map<String, Object>> getList(@NotNull Map<String, Object> map) {
        map = this.listParamFilter(map);
        if (this.listValidate(map)) {
            return this.getMapper()._getList(map);
        }
        throw new IException(CommonErrorInfo.BODY_NOT_MATCH);
    }

    /**
     * <p>查询列表</p>
     *
     * @param model extends BaseModel
     * @return List<T> T extends BaseModel
     **/
    public List<Map<String, Object>> getList(@NotNull T model) {
        return this.getList(model.toMap());
    }

    /**
     * <p>查询列表</p>
     *
     * @param column column
     * @param value  value
     * @return List<T> T extends BaseModel
     **/
    public List<Map<String, Object>> getList(String column, Object value) {
        Map<String, Object> map = new HashMap<>(1);
        map.put(column, value);
        return this.getList(map);
    }

    /**
     * <p>查询列表</p>
     *
     * @param keyValues 键值对,偶数长度。key, value, key, value...
     * @return List<Map < String, Object>>
     **/
    public List<Map<String, Object>> getList(@NotNull Object... keyValues) {
        Map<String, Object> map = new HashMap<>();
        if (keyValues.length > 0) {
            int length = keyValues.length;
            for (int i = 0; i < keyValues.length; i++) {
                if (i % 2 == 0 && length >= i + 1) {
                    map.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
                }
            }
            return this.getList(map);
        }
        return this.getList(map);
    }

    @Override
    public List<T> getNestList(@NotNull Map<String, Object> map) {
        map = this.listParamFilter(map);
        if (this.listValidate(map)) {
            return this.getMapper()._getNestList(map);
        }
        throw new IException(CommonErrorInfo.BODY_NOT_MATCH);
    }

    public List<T> getNestList(@NotNull T model) {
        return this.getNestList(model.toMap());
    }

    public List<T> getNestList(String column, Object value) {
        Map<String, Object> map = new HashMap<>(1);
        map.put(column, value);
        return this.getNestList(map);
    }

    public List<T> getNestList(@NotNull Object... keyValuesArray) {
        if (keyValuesArray.length > 0) {
            Map<String, Object> map = new HashMap<>();
            int length = keyValuesArray.length;
            for (int i = 0; i < keyValuesArray.length; i++) {
                if (i % 2 == 0 && length >= i + 1) {
                    map.put(String.valueOf(keyValuesArray[i]), keyValuesArray[i + 1]);
                }
            }
            return this.getNestList(map);
        }
        return List.of();
    }

    public Map<String, Object> listParamFilter(@NotNull Map<String, Object> map) {
        int pageIndex;
        try {
            pageIndex = Integer.parseInt(String.valueOf(map.get("pageIndex")));
            if (pageIndex <= 0) {
                pageIndex = 1;
            }
        } catch (Exception e) {
            log.warn("不规范的pageIndex参数");
            pageIndex = 1;
        }
        int pageSize;
        try {
            pageSize = Integer.parseInt(String.valueOf(map.get("pageSize")));
            if (pageSize <= 0) {
                log.warn("不规范的pageSize参数");
                pageSize = 10;
            }
        } catch (Exception e) {
            log.warn("不规范的pageSize参数");
            pageSize = 10;
        }
        map.put("pageIndex", (pageIndex - 1) * pageSize);
        map.put("pageSize", pageSize);
        return map;
    }

    public boolean listValidate(@NotNull Map<String, Object> map) {
        log.info("查询列表操作参数: {}", JSON.toJSONString(map));
        return true;
    }

    @Override
    public int getTotal(@NotNull Map<String, Object> map) {
        return this.getMapper()._getTotal(map);
    }

    public int getTotal(@NotNull Object... keyValuesArray) {
        Map<String, Object> map = new HashMap<>();
        int length = keyValuesArray.length;
        for (int i = 0; i < keyValuesArray.length; i++) {
            if (i % 2 == 0 && length >= i + 1) {
                map.put(String.valueOf(keyValuesArray[i]), keyValuesArray[i + 1]);
            }
        }
        return this.getTotal(map);
    }

    public int getTotal() {
        return this.getTotal(Map.of());
    }

    @Transactional
    public void importExcel(@NotNull MultipartFile multipartFile, @NotNull Integer headerRowNumber) {
        try (InputStream inputStream = multipartFile.getInputStream()) {
            ModelDataListener modelDataListener = new ModelDataListener();
            EasyExcel.read(inputStream, modelDataListener).headRowNumber(headerRowNumber).doReadAllSync();
            List<T> modelData = modelDataListener.mapToModelData(this.getModelClazz());
            for (int i = 0; i < modelData.size(); i += 25) {
                int end = Math.min(i + 25, modelData.size());
                this.batchSave(modelData.subList(i, end));
            }
        } catch (IOException e) {
            log.error("读取表格数据异常: ", e);
            throw new IException("导入表格数据失败");
        }
    }

    public void exportExcel(String fileName, List<Map<String, Object>> data, HttpServletResponse response) {
        if (StringUtil.isEmpty(fileName)) {
            throw new IException("请指定文件名");
        }
        try {
            fileName = URLEncoder.encode(fileName, StandardCharsets.UTF_8);
            if (StringUtil.isEmpty(fileName)) {
                fileName = this.getModelClazz().getAnnotation(Data.class).title();
            }
            if (!fileName.endsWith(".xlsx") && !fileName.endsWith(".xls")) {
                fileName += ".xlsx";
            }
            List<Field> modelBaseFields = this.getModelBaseFields();
            List<String> dataKeyList = modelBaseFields.stream().map(Field::getName).toList();
            List<String> dataTitleList = modelBaseFields.stream().map(item -> {
                String title = item.getAnnotation(Column.class).title();
                if (StringUtil.isEmpty(title)) {
                    return item.getName();
                }
                return title;
            }).toList();
            ExcelUtil.writeOneSheetExcel(data, dataKeyList, dataTitleList, null, null, fileName, response);
        } catch (IOException e) {
            log.error("异常: ", e);
            response.reset();
            response.setContentType("application/json");
            response.setCharacterEncoding("utf-8");
            throw new IException("Excel导出失败", e);
        }
    }

    @SuppressWarnings("unchecked")
    public Class<T> getModelClazz() {
        return (Class<T>) CacheTool.getServiceModelGeneric(this.getClass());
    }

    @SuppressWarnings("unchecked")
    public Class<M> getMapperClazz() {
        return (Class<M>) CacheTool.getServiceMapperGeneric(this.getClass());
    }

    public List<Field> getModelFields() {
        return CacheTool.getServiceModelGenericFields(this.getClass());
    }

    public List<Field> getModelBaseFields() {
        return CacheTool.getServiceModelGenericBaseFields(this.getClass());
    }

    public Map<String, Field> getModelMapBaseFields() {
        return this.getModelBaseFields().stream().collect(Collectors.toMap(Field::getName, value -> value));
    }

    public Field getModelPrimaryField() {
        return CacheTool.getModelPrimaryField(this.getModelClazz());
    }

}