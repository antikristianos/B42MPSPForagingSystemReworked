-- World-integration gate. Regular Vanilla findings/XP are not intercepted in this candidate.
local moduleName = "B42MPSPForagingSystemReworked"
local searchStates = {}
local ticks = 0
local missingReported = false
local lastSearch = {}

local function bridge()
    if B42ForagingBridge then return B42ForagingBridge end
    if not missingReported then
        missingReported = true
        print("[B42Foraging] ERROR Java bridge unavailable; check ZombieBuddy approval and Java loading")
    end
    return nil
end

local function onTick()
    ticks = ticks + 1
    if ticks % 120 ~= 0 then return end
    local api = bridge()
    if not api then return end
    if isServer() then api.beginWorld(); return end
    if not ISSearchManager then return end
    for index = 0, getNumActivePlayers() - 1 do
        local player = getSpecificPlayer(index)
        if player then
            local manager = ISSearchManager.getManager(player)
            local enabled = manager and manager.isSearchMode == true
            if enabled or searchStates[index] ~= enabled then
                searchStates[index] = enabled
                if isClient() then
                    sendClientCommand(player, moduleName, "search", { enabled = enabled })
                elseif enabled then
                    api.observePlayer(player)
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
            api.observePlayer(player)
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
    for index = 0, getNumActivePlayers() - 1 do
        local player = getSpecificPlayer(index)
        if player and player:getOnlineID() == args.onlineId then
            local manager = ISSearchManager and ISSearchManager.getManager(player)
            if not manager or not manager.isSearchMode then return end
            if math.floor(player:getX()) ~= args.x or math.floor(player:getY()) ~= args.y or math.floor(player:getZ()) ~= args.z then return end
            local square = player:getCell():getGridSquare(args.x, args.y, args.z)
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
