package com.sky.controller.admin;


import com.sky.dto.DishDTO;
import com.sky.dto.DishPageQueryDTO;
import com.sky.entity.Dish;
import com.sky.result.PageResult;
import com.sky.result.Result;
import com.sky.service.DishService;
import com.sky.vo.DishVO;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.annotations.Update;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/admin/dish")
@Slf4j
public class DishController {

    @Autowired
    private DishService dishService;
    @Autowired
    private RedisTemplate redisTemplate;


    /**
     * 新增菜品和口味
     * @param dishDTO
     * @return
     */
    @PostMapping
    public Result add(@RequestBody DishDTO dishDTO) {
        log.info("新增菜品：{}", dishDTO);
        dishService.addWithFlavor(dishDTO);
        //清除缓存
        String key = "dish:" + dishDTO.getCategoryId();
        clenCach(key);
        return Result.success();
    }

    /**
     * 分页查询
     * @param dishPageQueryDTO
     * @return
     */
    @GetMapping("/page")
    public Result<PageResult> page(DishPageQueryDTO dishPageQueryDTO) {
        log.info("分页查询{}", dishPageQueryDTO);
        PageResult pageResult = dishService.pageQuery(dishPageQueryDTO);
        return Result.success(pageResult);
    }

    /**
     * 删除菜品
     * @param ids
     * @return
     */
    @DeleteMapping
    public Result delete(@RequestParam List<Long> ids) {

        log.info("删除菜品的id：{}",ids);
        dishService.delete(ids);
        //清除缓存
        clenCach("dish_*");


        return Result.success();
    }

    /**
     * 根据id查询
     */
    @GetMapping("{id}")
    public Result<DishVO> getByIdWithFlavor(@PathVariable Long id) {
        DishVO dishVO = dishService.getByIdWithFlavor(id);
        return Result.success(dishVO);
    }

    /**
     * 修改菜品
     * @param dishDTO
     * @return
     */
    @PutMapping()
    public Result update(@RequestBody DishDTO dishDTO) {

        dishService.updateWithFlavor(dishDTO);
        //清除缓存
        clenCach("dish_*");

        return Result.success();

    }


    /**
     * 起售停售菜品
     * @param status
     * @param id
     * @return
     */
    @PostMapping("/status/{status}")
    public Result<String> startOrStop(@PathVariable Integer status,Long id) {

        dishService.startOrStop(status,id);

        clenCach("dish_*");

        return Result.success();

    }

    private void clenCach(String patten){
        Set keys = redisTemplate.keys(patten);
        redisTemplate.delete(keys);
    }

    @GetMapping("/list")
    public Result<List<Dish>> list(Long categoryId) {
        List<Dish> list = dishService.list(categoryId);
        return Result.success(list);

    }

}
