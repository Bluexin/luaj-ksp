function infoStr(what)
    return tostring(what) .. " (" .. type(what) .. ")"
end

function assert_equals(expected, actual, _path)
    local path = _path or "undefined"
    print("Checking " .. path)
    assert(expected == actual, "Expected " .. infoStr(actual) .. " to be " .. infoStr(expected) .. " (at " .. path .. ")")
end

_t = testing.testValue
