require "Foraging/forageSystem"

-- Authoritative helpers. Never replace the original functions used by bonus/track/world-item paths.
B42ForagingRules = {}
local rules = B42ForagingRules
local function clamp(value, minimum, maximum) return math.min(math.max(value, minimum), maximum) end
local function stance(player)
    return math.max(forageSystem.getAimVisionBonus(player), forageSystem.getSneakVisionBonus(player), 1)
end

function rules.enabled(player)
    if player:isDead() or player:getVehicle() or player:isRunning() or (player:isSprinting() and player:isJustMoved()) then return false end
    local stats = player:getStats()
    if stats and (stats:getNumVeryCloseZombies() > 0 or (stats:getNumVisibleZombies() >= 3 and stats:getNumChasingZombies() >= 3)) then return false end
    return true
end

function rules.radius(player)
    local bonus = forageSystem.getTraitVisionBonus(player) + forageSystem.getProfessionVisionBonus(player)
    local minimum, maximum = forageSystem.minVisionRadius + bonus, forageSystem.maxVisionRadius + bonus
    local radius = minimum + (maximum - minimum) * player:getPerkLevel(Perks.PlantScavenging) / 10
    for _, factor in ipairs({ forageSystem.getPanicPenalty(player), forageSystem.getBodyPenalty(player),
        forageSystem.getExhaustionPenalty(player), forageSystem.getClothingPenalty(player),
        forageSystem.getWeatherPenalty(player, player:getCurrentSquare()), forageSystem.getMovementVisionPenalty(player) }) do radius = radius * factor end
    return clamp(radius * stance(player), forageSystem.minVisionRadius, forageSystem.visionRadiusCap)
end

function rules.position(player, square)
    return not SafeHouse or SafeHouse.isSafehouseAllowLoot(square, player)
end

function rules.focus(player, biome, focus)
    local category = forageSystem.catDefs[focus]
    if not category or not category.zones[biome] or category.zones[biome] <= 0 then return 0 end
    local perk = Perks.FromString(category.identifyCategoryPerk)
    if not perk then return 0 end
    local level = player:getPerkLevel(perk)
    if level < category.identifyCategoryLevel then return 0 end
    return category.focusChanceMin + math.abs(category.focusChanceMax - category.focusChanceMin) / math.max(10 - level, 1)
end

function rules.item(biome, category)
    local zone = forageSystem.lootTable[biome]
    local entries = zone and zone.categories[category]
    if not entries then return nil end
    local names, weights, total = {}, {}, 0
    local rain = getClimateManager():getPrecipitationIntensity()
    local snow = getClimateManager():getSnowStrength()
    local puddles = getPuddlesManager():getPuddlesSize()
    local day = forageSystem.getTimeOfDay()
    for name, entry in pairs(entries.items) do
        local definition = entry.definition
        -- Category month capacity already owns seasons. No item-month multiplier/filter here.
        local weight = math.max(0, (definition.zones[biome] or 0)
            * forageSystem.getWeatherMulti(definition, rain, puddles, snow)
            * forageSystem.getTimeOfDayBonus(definition, day))
        if weight > 0 and weight < math.huge then names[#names + 1] = name; weights[name] = weight; total = total + weight end
    end
    if total <= 0 or total >= math.huge then return nil end
    table.sort(names)
    local roll, prefix = ZombRandFloat(0, 1) * total, 0
    for _, name in ipairs(names) do prefix = prefix + weights[name]; if roll < prefix then return name end end
    return nil
end

function rules.inspect(player, item, category, biome, square, rawLight)
    local detail = { rawLight = rawLight }
    local function done(valid, reason) detail.valid = valid; detail.reason = reason; return detail end
    local definition, catDef, zoneDef = forageSystem.itemDefs[item], forageSystem.catDefs[category], forageSystem.zoneDefs[biome]
    if not definition then return done(false, "missing_item") end
    if not catDef then return done(false, "missing_category") end
    if not zoneDef then return done(false, "missing_biome") end
    if not square then return done(false, "missing_square") end
    local climate = getClimateManager()
    detail.outside = square:isOutside()
    detail.daylight = climate:getDayLightStrength()
    detail.torch = player:getTorchStrength()
    detail.rain = climate:getPrecipitationIntensity(); detail.snow = climate:getSnowStrength()
    if getGameTime then local time = getGameTime(); if time then detail.hour = time:getTimeOfDay() end end
    local dx, dy = player:getX() - square:getX(), player:getY() - square:getY()
    detail.distance = math.sqrt(dx * dx + dy * dy)
    for _, test in ipairs(forageSystem.isForageableFuncs) do
        local callback = type(test) == "string" and forageSystem[test] or test
        if test ~= "isValidMonthInternal" and callback ~= forageSystem.isValidMonthInternal then
            local name = type(test) == "string" and test or "custom"
            if type(callback) ~= "function" then return done(false, "missing_predicate:" .. name) end
            if not callback(player, definition, zoneDef) then return done(false, "predicate:" .. name) end
        end
    end
    if not forageSystem.isValidSquare(square, definition, catDef) then return done(false, "invalid_square") end
    local level = forageSystem.getPerkLevel(player, definition)
    detail.level = level
    local bonus = forageSystem.getTraitVisionBonus(player) + forageSystem.getProfessionVisionBonus(player)
    local minimum, maximum = forageSystem.minVisionRadius, forageSystem.maxVisionRadius + bonus
    local radius = clamp(minimum + forageSystem.getLevelVisionBonus(level) + bonus, minimum, maximum)
    for _, factor in ipairs({ forageSystem.getPanicPenalty(player), forageSystem.getBodyPenalty(player),
        forageSystem.getExhaustionPenalty(player), forageSystem.getClothingPenalty(player),
        forageSystem.getDifficultyPenalty(level), forageSystem.getItemSizePenalty(definition.itemSize or 1),
        forageSystem.getWeatherPenalty(player, square) }) do radius = radius * factor end
    local vision = stance(player) * forageSystem.getCategoryBonus(player, catDef) * forageSystem.getHungerBonus(player, definition)
    radius = radius * vision; maximum = math.min(maximum * vision, forageSystem.visionRadiusCap); minimum = minimum * vision
    local light = rawLight
    if square == player:getCurrentSquare() then light = math.max(light, math.min(1, detail.torch)) end
    if light > 0.8 then light = 1 end
    if detail.outside then light = math.max(light, detail.daylight) end
    detail.effectiveLight = light
    local penalty = clamp(1 - (1 - light) * forageSystem.getDarknessEffectReduction(player), 1 - forageSystem.lightPenaltyMax / 100, 1)
    -- The server has no per-player native darkMulti. Until its table-light exception is proven,
    -- fail closed away from the native on-square distance instead of assuming a lit surface.
    if dx * dx + dy * dy > 1.5 * 1.5 and 1 - penalty >= forageSystem.lightPenaltyCutoff / 100 then detail.radius = forageSystem.darkVisionRadius; return done(false, "darkness_cutoff") end
    if 1 - penalty >= forageSystem.lightPenaltyCutoff / 100 then radius = forageSystem.darkVisionRadius else radius = radius * penalty end
    radius = clamp(radius, minimum, maximum)
    detail.radius = radius
    if dx * dx + dy * dy > radius * radius then return done(false, "outside_item_radius") end
    return done(true, "accepted")
end

function rules.valid(player, item, category, biome, square, rawLight)
    return rules.inspect(player, item, category, biome, square, rawLight).valid
end
