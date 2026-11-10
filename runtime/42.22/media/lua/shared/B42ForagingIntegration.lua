-- World-integration gate. Regular Vanilla findings/XP are not intercepted in this candidate.
local moduleName = "B42MPSPForagingSystemReworked"
local searchStates = {}
local ticks = 0
local missingReported = false
local lastSearch = {}
local rulesInstalled = false

local function bridge()
    if B42ForagingBridge then
        if not isClient() and B42ForagingRules and not rulesInstalled then
            B42ForagingBridge.setSearchRules(B42ForagingRules)
            rulesInstalled = true
        end
        return B42ForagingBridge
    end
    if not missingReported then
        missingReported = true
        print("[B42Foraging] ERROR Java bridge unavailable; check ZombieBuddy approval and Java loading")
    end
    return nil
end

local function onTick()
    ticks = ticks + 1
    local api = bridge()
    if not api then return end
    if not isClient() then api.tick() end
    if isServer() and ticks % 10 == 0 then
        local players = getOnlinePlayers()
        for index = 0, players:size() - 1 do
            local player = players:get(index)
            local request = api.requestLight(player)
            if request then sendServerCommand(player, moduleName, "light", request) end
        end
    end
    if ticks % 10 ~= 0 then return end
    if isServer() then if ticks % 120 == 0 then api.beginWorld() end; return end
    if not ISSearchManager then return end
    for index = 0, getNumActivePlayers() - 1 do
        local player = getSpecificPlayer(index)
        if player then
            local manager = ISSearchManager.getManager(player)
            local enabled = manager and manager.isSearchMode == true
            local window = ISSearchWindow and ISSearchWindow.players and ISSearchWindow.players[player]
            local focus = window and window.searchFocusCategory or "None"
            if type(focus) ~= "string" then focus = "None" end
            local previous = searchStates[index]
            if (enabled and ticks % 120 == 0) or not previous or previous.enabled ~= enabled or previous.focus ~= focus then
                searchStates[index] = { enabled = enabled, focus = focus }
                if isClient() then
                    sendClientCommand(player, moduleName, "search", { enabled = enabled, focus = focus })
                elseif enabled then
                    api.setSearchState(player, true, focus)
                    api.observeLocalLight(player)
                else api.cancelSearch(player) end
            end
        end
    end
end

local function onClientCommand(module, command, player, args)
    if module ~= moduleName or not isServer() or not player then return end
    local api = bridge()
    if not api or type(args) ~= "table" then return end
    if command == "search" then
        if args.enabled == true then
            local id = player:getOnlineID()
            local now = getTimestampMs()
            if lastSearch[id] and now - lastSearch[id] < 500 then return end
            lastSearch[id] = now
            api.setSearchState(player, true, type(args.focus) == "string" and args.focus or "None")
            local request = api.requestLight(player)
            if request then sendServerCommand(player, moduleName, "light", request) end
        else
            lastSearch[player:getOnlineID()] = nil
            api.cancelSearch(player)
        end
    elseif command == "light" then api.acceptLight(player, args) end
end

local function onServerCommand(module, command, args)
    if module ~= moduleName or command ~= "light" or not isClient() or type(args) ~= "table" then return end
    if type(args.x) ~= "number" or type(args.y) ~= "number" or type(args.z) ~= "number" then return end
    local targetX, targetY, targetZ = args.targetX or args.x, args.targetY or args.y, args.targetZ or args.z
    if type(targetX) ~= "number" or type(targetY) ~= "number" or type(targetZ) ~= "number" then return end
    if targetX ~= targetX or targetY ~= targetY or targetZ ~= targetZ then return end
    if targetX ~= math.floor(targetX) or targetY ~= math.floor(targetY) or targetZ ~= math.floor(targetZ) then return end
    if math.abs(targetX - args.x) > 4 or math.abs(targetY - args.y) > 4 or targetZ ~= args.z then return end
    for index = 0, getNumActivePlayers() - 1 do
        local player = getSpecificPlayer(index)
        if player and player:getOnlineID() == args.onlineId then
            local manager = ISSearchManager and ISSearchManager.getManager(player)
            if not manager or not manager.isSearchMode then return end
            if math.floor(player:getX()) ~= args.x or math.floor(player:getY()) ~= args.y or math.floor(player:getZ()) ~= args.z then return end
            local square = player:getCell():getGridSquare(targetX, targetY, targetZ)
            if not square then return end
            local value = square:getLightLevel(index)
            if type(value) ~= "number" or value ~= value or value < 0 or value > 1 then return end
            sendClientCommand(player, moduleName, "light", { requestId = args.requestId, session = args.session, light = value })
            return
        end
    end
end

local function beginWorld()
    local api = bridge()
    if api and not isClient() then api.beginWorld() end
end

Events.OnTick.Add(onTick)
Events.OnGameStart.Add(beginWorld)
Events.OnServerStarted.Add(beginWorld)
Events.OnClientCommand.Add(onClientCommand)
Events.OnServerCommand.Add(onServerCommand)
