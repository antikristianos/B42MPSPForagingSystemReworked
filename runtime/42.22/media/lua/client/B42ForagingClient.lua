require "Foraging/ISForageIcon"
require "Foraging/ISSearchManager"

B42ForagingClient = {}
local client, states = B42ForagingClient, {}
local function age() return getGameTime():getWorldAgeHours() end
local function finite(value) return type(value) == "number" and value == value and math.abs(value) < math.huge end
local function own(id) return type(id) == "string" and #id <= 64 and id:sub(1, 5) == "b42f:" and not id:find("%c") end
local function remove(state, id)
    local icon = state.icons[id]
    if icon then
        icon:removeIsoMarker(); icon:removeWorldMarker(); icon:removeFromUIManager()
        local manager = icon.manager
        manager.forageIcons[id], manager.activeIcons[id], manager.closeIcons[id], manager.seenIcons[id], manager.xpIcons[id] = nil, nil, nil, nil, nil
        icon.zoneData.forageIcons[id] = nil
    end
    state.icons[id], state.records[id] = nil, nil
end
local function clear(state)
    local ids = {}; for id in pairs(state.records) do ids[#ids + 1] = id end
    for _, id in ipairs(ids) do remove(state, id) end
end
function client.control(player, enabled, nonce)
    local index = player:getPlayerNum()
    local state = states[index]
    if not state then state = {icons={},records={},sequence=0}; states[index] = state end
    if state.nonce ~= nonce then clear(state); state.epoch=nil; state.sequence=0 end
    state.nonce, state.enabled = nonce, enabled
    if not enabled then clear(state) end
end

local Icon = ISForageIcon:derive("B42SharedForageIcon")
function Icon:canPickup()
    local record = self.b42Record
    local id = isClient() and self.character:getOnlineID() or self.player
    return record and age() < record.expires - 1 / 60 and (record.busyBy == nil or record.busyBy == id)
end
function Icon:isValid() return self.b42Record and age() < self.b42Record.expires end
function Icon:checkIsForageable() return self:isValid() end
function Icon:doSearchFocusCheck() end
function Icon:spotIcon()
    self:setIsSeen(true); self:setIsNoticed(true); self:setIsSeenThisUpdate(true)
    self.manager.seenIcons[self.iconID], self.manager.xpIcons[self.iconID] = self.iconID, self.iconID
    self:getItemList(); self:addIsoMarker()
end
function Icon:getItemList()
    if not self.itemList and self.b42Record then
        self.itemList = ArrayList.new()
        for _, item in ipairs(self.b42Record.items) do
            for index = 1, item.count do
                local object = instanceItem(item.type)
                if object then
                    if instanceof(object,"Food") then
                        if item.poisonPower then object:setPoisonPower(item.poisonPower) end
                        if item.poisonDetectionLevel then object:setPoisonDetectionLevel(item.poisonDetectionLevel) end
                    end
                    self.itemList:add(object)
                end
            end
        end
        self.icon.itemList = self.itemList
        if not self.itemList:isEmpty() then
            self.itemObj=self.itemList:get(0); self.icon.itemObj=self.itemObj; self.itemTexture=self.itemObj:getTexture()
            self:findTextureCenter(); self:findPinOffset(); self:checkForPoison()
        end
    end
    return self.itemList
end
function Icon:updateAlpha()
    ISBaseIcon.updateAlpha(self)
    if not self.b42Record then return end
    local fade = math.max(0, math.min(1, (self.b42Record.expires - age() - 1 / 60) / (4 / 60)))
    self:setAlpha(self:getAlpha() * fade)
    if self.isoMarker then self.isoMarker:setAlpha(self:getAlpha()) end
end
function Icon:doForage(x, y, context, container)
    if not self:canPickup() then return false end
    if context then context:hideAndChildren() end
    if not self:getIsSeen() or self:getAlpha() <= 0 then return false end
    local square = self.character:getCell():getGridSquare(self.xCoord,self.yCoord,self.zCoord)
    local target = container or getPlayerInventory(self.player).inventory or self.character:getInventory()
    if not square or not target:isItemAllowed(self.itemObj) or not luautils.walkAdj(self.character,square) then return false end
    -- Only identity/container go through the native action. The server already owns the item bundle.
    ISTimedActionQueue.add(ISForageAction:new(self.character,self.iconID,{},target,self.itemType))
    return true
end
function Icon:doContextMenu(context)
    if not self:canPickup() then return false end
    return ISBaseIcon.doContextMenu(self, context)
end
local function materialize(player, state, record)
    local manager = ISSearchManager.getManager(player)
    local square = player:getCell():getGridSquare(record.x, record.y, record.z)
    if not manager or not square or not forageSystem.itemDefs[record.itemType] or not forageSystem.catDefs[record.category] then return end
    local object = instanceItem(record.itemType); if not object then return end
    local data = {id=record.id,x=record.x,y=record.y,z=record.z,itemType=record.itemType,catName=record.category,count=record.count,
        itemObj=object,canRollForSearchFocus=false,zoneid="b42f-ui"}
    local zone = {id="b42f-ui",forageIcons={[record.id]=data}}
    local icon = ISForageIcon.new(Icon, manager, data, zone)
    icon.b42Record, icon.isValidSquare = record, true
    icon.onMouseDoubleClick, icon.onClickContext = Icon.doForage, Icon.doForage
    manager.forageIcons[record.id], manager.activeIcons[record.id], state.icons[record.id] = icon, icon, icon
    icon:getGridSquare(); icon:getItemList(); icon:spotIcon(); icon:addToUIManager()
end
local function validRecord(record)
    if type(record) ~= "table" or not own(record.id) or not finite(record.expires) or not finite(record.count) or record.count < 1 or record.count > 10000 or record.count ~= math.floor(record.count)
        or type(record.itemType) ~= "string" or type(record.category) ~= "string" or type(record.items) ~= "table" then return false end
    for _, key in ipairs({"x","y","z"}) do if not finite(record[key]) or record[key] ~= math.floor(record[key]) or math.abs(record[key]) > 2147483647 then return false end end
    if record.busyBy ~= nil and (not finite(record.busyBy) or record.busyBy ~= math.floor(record.busyBy)) then return false end
    local count = 0
    for _, item in ipairs(record.items) do
        if type(item) ~= "table" or type(item.type) ~= "string" or #item.type > 256 or item.type:find("%c") or not finite(item.count) or item.count < 1 or item.count ~= math.floor(item.count) then return false end
        count=count+item.count; if count>10000 then return false end
        for _, key in ipairs({"poisonPower","poisonDetectionLevel"}) do if item[key] ~= nil and (not finite(item[key]) or math.abs(item[key]) > 2147483647 or item[key] ~= math.floor(item[key])) then return false end end
    end
    return count == record.count
end
function client.apply(player, packet)
    local state = states[player:getPlayerNum()]
    local recipient = isClient() and player:getOnlineID() or player:getPlayerNum()
    if not state or not state.enabled or type(packet) ~= "table" or packet.nonce ~= state.nonce or packet.onlineId ~= recipient
        or type(packet.epoch) ~= "string" or #packet.epoch > 64 or not finite(packet.sequence) or packet.sequence ~= math.floor(packet.sequence)
        or packet.sequence > 9007199254740991 or packet.sequence <= state.sequence or type(packet.reset) ~= "boolean" or type(packet.finds) ~= "table" or type(packet.removed) ~= "table" then return false end
    if packet.epoch ~= state.epoch and packet.reset ~= true then return false end
    for _, record in ipairs(packet.finds) do if not validRecord(record) then return false end end
    for _, id in ipairs(packet.removed) do if not own(id) then return false end end
    if packet.reset then clear(state) end
    state.epoch, state.sequence = packet.epoch, packet.sequence
    for _, id in ipairs(packet.removed) do remove(state, id) end
    for _, record in ipairs(packet.finds) do
        local previous = state.records[record.id]
        if previous and (record.expires ~= previous.expires or record.x ~= previous.x or record.y ~= previous.y or record.z ~= previous.z or record.itemType ~= previous.itemType or record.category ~= previous.category or record.count ~= previous.count) then
            print("[B42Foraging] ERROR immutable find changed id=" .. record.id)
        elseif record.expires > age() then
            state.records[record.id] = record
            local icon = state.icons[record.id]
            if icon then icon.b42Record=record else materialize(player, state, record) end
        end
    end
    print("[B42Foraging] FIND_DELTA sequence=" .. packet.sequence .. " reset=" .. tostring(packet.reset) .. " changed=" .. #packet.finds .. " removed=" .. #packet.removed)
    return true
end
local originalLoad = ISSearchManager.loadIcons
function ISSearchManager:loadIcons(...)
    if not isClient() then
        for icon in pairs(self.iconStack) do
            if not icon.isBonusIcon and icon.catName ~= "Tracks" and not own(icon.id) then self.iconStack[icon]=nil; self.iconQueue=math.max(0,self.iconQueue-1) end
        end
    end
    return originalLoad(self, ...)
end
Events.OnTick.Add(function()
    for index, state in pairs(states) do
        local player = getSpecificPlayer(index)
        if player and state.enabled then
            local removed = {}
            for id, record in pairs(state.records) do
                if record.expires <= age() then removed[#removed+1]=id
                elseif not state.icons[id] then materialize(player,state,record) end
            end
            for _, id in ipairs(removed) do remove(state,id) end
        end
    end
end)
